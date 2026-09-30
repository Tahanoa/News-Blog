package org.example.newsblog.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.example.newsblog.user.AppUser;
import org.example.newsblog.user.Role;
import org.example.newsblog.user.UserRepository;
import org.example.newsblog.security.JwtTokens;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalAiApiTests {
    private static final HttpServer OLLAMA = startOllama();
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger DELAY_MS = new AtomicInteger();
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired JwtTokens tokens;
    @Autowired PasswordEncoder passwords;
    private String token;

    private static HttpServer startOllama() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", exchange -> {
                CALLS.incrementAndGet();
                LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try {
                    Thread.sleep(DELAY_MS.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(STATUS.get(), bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
        registry.add("ai.ollama.read-timeout", () -> "500ms");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:ai-tests;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void reset() {
        STATUS.set(200);
        CALLS.set(0);
        DELAY_MS.set(0);
        BODY.set("{\"message\":{\"content\":\"متن نهایی\",\"thinking\":\"private analysis\"},\"done\":true,\"done_reason\":\"stop\"}");
        AppUser admin = users.findByUsername("ai-admin").orElseGet(() -> users.saveAndFlush(
                new AppUser("ai-admin", "ai-admin@example.com", passwords.encode("test-password-123"), Role.ADMIN)));
        token = tokens.issue(admin).accessToken();
    }

    @AfterAll
    static void stop() {
        OLLAMA.stop(0);
    }

    private HttpResponse<String> request(String path, String body, boolean authenticated) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/ai/" + path))
                .timeout(Duration.ofSeconds(10));
        if (authenticated) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rejectsUnauthenticatedCallsWithoutContactingOllama() throws Exception {
        assertEquals(401, request("health", null, false).statusCode());
        assertEquals(401, request("generate", "{\"prompt\":\"hello\"}", false).statusCode());
        assertEquals(0, CALLS.get());
    }

    @Test
    void validatesInputBeforeContactingOllama() throws Exception {
        for (String body : new String[]{"{}", "{\"prompt\":\"   \"}", "{invalid",
                mapper.writeValueAsString(Map.of("prompt", "x".repeat(12001)))}) {
            assertEquals(400, request("generate", body, true).statusCode());
        }
        assertEquals(0, CALLS.get());
    }

    @Test
    void sendsOneCompleteRequestAndReturnsFinalContentOnly() throws Exception {
        String prompt = "برای خبر کتابخانه، تیتر و خلاصه بنویس.";
        var response = request("generate", mapper.writeValueAsString(Map.of("prompt", prompt)), true);
        assertEquals(200, response.statusCode());
        var result = mapper.readTree(response.body());
        assertEquals("متن نهایی", result.path("text").asText());
        assertFalse(result.path("truncated").asBoolean());
        assertFalse(response.body().contains("private analysis"));
        var sent = mapper.readTree(LAST_REQUEST.get());
        assertEquals("qwen3:4b-instruct", sent.path("model").asText());
        assertFalse(sent.path("stream").asBoolean());
        assertEquals(prompt, sent.path("messages").get(1).path("content").asText());
        assertEquals(1024, sent.path("options").path("num_predict").asInt());
    }

    @Test
    void healthChecksInstalledModelWithoutGeneratingText() throws Exception {
        BODY.set("{\"models\":[{\"name\":\"qwen3:4b-instruct\"}]}");
        assertEquals(200, request("health", null, true).statusCode());
        BODY.set("{\"models\":[]}");
        var response = request("health", null, true);
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("MODEL_NOT_FOUND"));
    }

    @Test
    void reportsUpstreamFailureAndMalformedOutput() throws Exception {
        STATUS.set(500);
        assertEquals(502, request("generate", "{\"prompt\":\"hello\"}", true).statusCode());
        STATUS.set(404);
        assertEquals(503, request("generate", "{\"prompt\":\"hello\"}", true).statusCode());
        STATUS.set(200);
        for (String body : new String[]{"not-json", "{}", "{\"message\":{\"content\":\"\"},\"done\":true}"}) {
            BODY.set(body);
            assertEquals(502, request("generate", "{\"prompt\":\"hello\"}", true).statusCode());
        }
    }

    @Test
    void flagsOutputThatReachedTheTokenLimit() throws Exception {
        BODY.set("{\"message\":{\"content\":\"متن\"},\"done\":true,\"done_reason\":\"length\"}");
        assertTrue(mapper.readTree(request("generate", "{\"prompt\":\"hello\"}", true).body())
                .path("truncated").asBoolean());
    }

    @Test
    void timesOutAndReleasesGenerationSlot() throws Exception {
        DELAY_MS.set(800);
        var response = request("generate", "{\"prompt\":\"hello\"}", true);
        assertEquals(504, response.statusCode());
        assertTrue(response.body().contains("OLLAMA_TIMEOUT"));
        DELAY_MS.set(0);
        assertEquals(200, request("generate", "{\"prompt\":\"hello\"}", true).statusCode());
    }
}
