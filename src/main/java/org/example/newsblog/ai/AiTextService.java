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
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("ai-local")
class AiTextService {
    private static final String SYSTEM_PROMPT = """
            تو دستیار نگارش و ویراستار فارسی هستی. فقط پاسخ نهایی را با فارسی روان و رسمی بنویس.
            تحلیل، روند فکر کردن و توضیح اضافه ننویس. قالب خواسته‌شده را رعایت کن.
            در بازنویسی و خلاصه خبر، نام‌ها، اعداد و زمان وقوع را دقیق حفظ کن.
            اطلاعات، آمار یا نقل‌قولی که در متن ورودی نیست اضافه نکن.
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
                "مدل نصب نیست. دستور ollama pull " + model + " را اجرا کنید.");
    }

    Generation generate(String prompt) {
        if (!generationSlot.tryAcquire()) {
            throw new AiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", "درخواست قبلی هنوز در حال پردازش است.");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "stream", false,
                    "messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", prompt)),
                    "options", Map.of("temperature", 0.3, "num_predict", 1024, "num_ctx", 8192)));
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
                        "مدل یا مسیر Ollama پیدا نشد؛ نصب مدل و آدرس سرویس را بررسی کنید.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiException(HttpStatus.BAD_GATEWAY, "OLLAMA_ERROR", "Ollama درخواست را پردازش نکرد.");
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
            throw new AiException(HttpStatus.GATEWAY_TIMEOUT, "OLLAMA_TIMEOUT", "مهلت پاسخ Ollama تمام شد.");
        } catch (IOException exception) {
            throw new AiException(HttpStatus.SERVICE_UNAVAILABLE, "OLLAMA_UNAVAILABLE",
                    "اتصال به Ollama برقرار نیست؛ برنامه Ollama را اجرا کنید.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiException(HttpStatus.SERVICE_UNAVAILABLE, "REQUEST_INTERRUPTED", "پردازش درخواست متوقف شد.");
        }
    }

    private static AiException invalidResponse() {
        return new AiException(HttpStatus.BAD_GATEWAY, "INVALID_OLLAMA_RESPONSE", "پاسخ Ollama معتبر نیست.");
    }

    record Health(String status, String model, boolean modelAvailable) {}
    record Generation(String model, String text, boolean truncated) {}

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
