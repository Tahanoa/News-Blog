package org.example.newsblog.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class AiTextService {
    private static final String SYSTEM_PROMPT = """
            You are a Persian text generation assistant and news editor. Follow the user's request.
            Always write the final output in fluent, formal Persian (Farsi), even if the instructions are English.
            Return only the final answer, without analysis, thinking, or extra explanations. Follow the requested format.
            When asked to generate text about a topic, generate it; a source article is not required.
            When asked to rewrite, summarize, or create a headline from supplied text, use only that text.
            Preserve names, numbers, and event dates exactly in rewrites and summaries. Do not add new facts.
            Do not invent real statistics, quotations, or events without a source when generating new text.
            """;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String baseUrl;
    private final String model;
    private final Duration readTimeout;
    private final Duration healthTimeout;
    private final Semaphore generationSlot = new Semaphore(1);

    AiTextService(@Value("${ai.ollama.base-url}") String baseUrl,
                  @Value("${ai.ollama.model}") String model,
                  @Value("${ai.ollama.connect-timeout}") Duration connectTimeout,
                  @Value("${ai.ollama.read-timeout}") Duration readTimeout,
                  @Value("${ai.ollama.health-timeout}") Duration healthTimeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.readTimeout = readTimeout;
        this.healthTimeout = healthTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    Health health() {
        JsonNode response = exchange(HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                .timeout(healthTimeout).GET().build());
        JsonNode models = response.get("models");
        if (models == null || !models.isArray()) {
            throw invalidResponse();
        }
        for (JsonNode item : models) {
            if (model.equals(item.path("name").asText()) || model.equals(item.path("model").asText())) {
                return new Health("UP", model, true);
            }
        }
        throw new AiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_FOUND",
                "Model is not installed. Run ollama pull " + model + ".");
    }

    Generation generate(String prompt) { return generate(prompt, SYSTEM_PROMPT, null, 1024); }

    NewsDraft newsDraft(String prompt) {
        var fields = Map.of(
                "title", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                "summary", Map.of("type", "string", "minLength", 1, "maxLength", 1000),
                "category", Map.of("type", "string", "minLength", 1, "maxLength", 80),
                "bodyHtml", Map.of("type", "string", "minLength", 1, "maxLength", 20000));
        var schema = Map.of("type", "object", "properties", fields,
                "required", List.of("title", "summary", "category", "bodyHtml"), "additionalProperties", false);
        String instructions = SYSTEM_PROMPT + """
                Return exactly one JSON object with English keys title, summary, category, bodyHtml.
                All four values must be written in Persian. Write a short headline, one-sentence summary,
                a short suitable category, and complete readable article HTML using paragraphs and headings.
                Use only facts from the user's supplied source. If only a topic is supplied, write general
                explanatory content without fabricating real news events, names, dates, statistics or quotes.
                Do not include images, cover, IDs, status, author, markdown fences or any commentary outside JSON.
                Keep title under 200 characters, summary under 1000, category under 80, bodyHtml under 20000.
                """;
        Generation result = generate(prompt, instructions, schema, 3072);
        if (result.truncated()) throw new AiException(HttpStatus.BAD_GATEWAY,"INCOMPLETE_AI_DRAFT",
                "The generated article was incomplete. Try a shorter source or request a shorter article.");
        try {
            JsonNode json = mapper.readTree(result.text());
            if (json == null || !json.isObject() || json.size() != 4) throw invalidResponse();
            String title = draftField(json, "title", 200), summary = draftField(json, "summary", 1000);
            String category = draftField(json, "category", 80), html = draftField(json, "bodyHtml", 20000);
            html = org.jsoup.Jsoup.clean(html, "", org.jsoup.safety.Safelist.relaxed().removeTags("img"),
                    new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
            if (html.length() > 20000 || org.jsoup.Jsoup.parseBodyFragment(html).text().isBlank()) throw invalidResponse();
            return new NewsDraft(model, title, summary, category, html);
        } catch (tools.jackson.core.JacksonException exception) { throw invalidResponse(); }
    }

    private String draftField(JsonNode json, String name, int max) {
        JsonNode field = json.get(name);
        if (field == null || !field.isString() || field.asText().isBlank() || field.asText().length() > max)
            throw invalidResponse();
        return field.asText().strip();
    }

    private Generation generate(String prompt, String systemPrompt, Object format, int maxTokens) {
        if (!generationSlot.tryAcquire()) {
            throw new AiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", "A previous request is still processing.");
        }
        try {
            var payload = new java.util.HashMap<String, Object>();
            payload.put("model", model); payload.put("stream", false);
            payload.put("messages", List.of(Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", prompt)));
            payload.put("options", Map.of("temperature", 0.3, "num_predict", maxTokens, "num_ctx", 8192));
            if (format != null) payload.put("format", format);
            String body = mapper.writeValueAsString(payload);
            JsonNode response = exchange(HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                    .timeout(readTimeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
            JsonNode content = response.path("message").get("content");
            if (content == null || !content.isString() || content.asText().isBlank()
                    || !response.path("done").asBoolean()) {
                throw invalidResponse();
            }
            // Explicitly read final content only, never message.thinking.
            return new Generation(model, content.asText(), "length".equals(response.path("done_reason").asText()));
        } finally {
            generationSlot.release();
        }
    }

    private JsonNode exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new AiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_FOUND",
                        "Ollama model or endpoint was not found. Check the installed model and service URL.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiException(HttpStatus.BAD_GATEWAY, "OLLAMA_ERROR", "Ollama could not process the request.");
            }
            try {
                JsonNode parsed = mapper.readTree(response.body());
                if (parsed == null || !parsed.isObject()) {
                    throw invalidResponse();
                }
                return parsed;
            } catch (tools.jackson.core.JacksonException exception) {
                throw invalidResponse();
            }
        } catch (HttpTimeoutException exception) {
            throw new AiException(HttpStatus.GATEWAY_TIMEOUT, "OLLAMA_TIMEOUT", "Ollama response timed out.");
        } catch (IOException exception) {
            throw new AiException(HttpStatus.SERVICE_UNAVAILABLE, "OLLAMA_UNAVAILABLE",
                    "Cannot connect to Ollama. Start the Ollama service.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiException(HttpStatus.SERVICE_UNAVAILABLE, "REQUEST_INTERRUPTED", "Request processing was interrupted.");
        }
    }

    private static AiException invalidResponse() {
        return new AiException(HttpStatus.BAD_GATEWAY, "INVALID_OLLAMA_RESPONSE", "Invalid Ollama response.");
    }

    record Health(String status, String model, boolean modelAvailable) {}
    record Generation(String model, String text, boolean truncated) {}
    record NewsDraft(String model, String title, String summary, String category, String bodyHtml) {}

    static class AiException extends RuntimeException {
        final HttpStatus status;
        final String code;

        AiException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
