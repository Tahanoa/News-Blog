package org.example.newsblog.security;

import org.example.newsblog.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtSecurityTests {
    static final String PASSWORD = "a-secure-test-password";
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired UserService service;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtTokens tokens;
    @Autowired JwtEncoder encoder;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ai.ollama.base-url", () -> "http://127.0.0.1:1");
    }

    @BeforeEach void clean() { users.deleteAll(); }

    HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    AppUser user(String name, Role role) {
        return users.saveAndFlush(new AppUser(name, name + "@example.com", passwords.encode(PASSWORD), role));
    }
    String token(AppUser user) { return tokens.issue(users.findById(user.getId()).orElseThrow()).accessToken(); }
    Object login(String name, String password) { return Map.of("username", name, "password", password); }

    @Test @Order(1)
    void registrationHashesPasswordAndLoginReturnsBearerToken() throws Exception {
        var registered = call("POST", "/api/auth/register", Map.of("username", "Reader.One",
                "email", "Reader@Example.com", "password", PASSWORD), null);
        assertEquals(201, registered.statusCode());
        assertEquals("USER", json(registered).path("role").asText());
        assertEquals("reader@example.com", json(registered).path("email").asText());
        assertTrue(json(registered).path("enabled").asBoolean());
        assertFalse(registered.body().contains("password"));
        var persisted = users.findByUsername("reader.one").orElseThrow();
        assertNotEquals(PASSWORD, persisted.getPasswordHash());
        assertTrue(persisted.getPasswordHash().startsWith("$2"));
        assertTrue(passwords.matches(PASSWORD, persisted.getPasswordHash()));
        var loggedIn = call("POST", "/api/auth/login", login("READER.ONE", PASSWORD), null);
        assertEquals(200, loggedIn.statusCode());
        assertEquals("Bearer", json(loggedIn).path("tokenType").asText());
        assertEquals(900, json(loggedIn).path("expiresIn").asLong());
        var me = call("GET", "/api/users/me", null, json(loggedIn).path("accessToken").asText());
        assertEquals(200, me.statusCode());
        assertEquals("reader.one", json(me).path("username").asText());
        assertTrue(me.headers().firstValue("Set-Cookie").isEmpty());
        assertTrue(loggedIn.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test @Order(2)
    void publicRegistrationCannotChooseRolesOrEnabledState() throws Exception {
        var body = Map.of("username", "attacker", "email", "attacker@example.com", "password", PASSWORD,
                "role", "ADMIN", "enabled", true);
        assertEquals(400, call("POST", "/api/auth/register", body, null).statusCode());
        assertTrue(users.findByUsername("attacker").isEmpty());
    }

    @Test @Order(3)
    void rejectsWeakInvalidAndDuplicateAccounts() throws Exception {
        for (Object body : List.of(
                Map.of("username", "reader", "email", "reader@example.com", "password", "short"),
                Map.of("username", "reader", "email", "bad", "password", PASSWORD),
                Map.of("username", "<script>", "email", "reader@example.com", "password", PASSWORD),
                Map.of("username", "reader", "email", "reader@example.com", "password", "\u0622".repeat(40)))) {
            assertEquals(400, call("POST", "/api/auth/register", body, null).statusCode());
        }
        user("duplicate", Role.USER);
        assertEquals(409, call("POST", "/api/auth/register", Map.of("username", "DUPLICATE",
                "email", "other@example.com", "password", PASSWORD), null).statusCode());
        assertEquals(409, call("POST", "/api/auth/register", Map.of("username", "other",
                "email", "DUPLICATE@EXAMPLE.COM", "password", PASSWORD), null).statusCode());
    }

    @Test @Order(4)
    void enforcesUserReporterAndAdminPermissions() throws Exception {
        AppUser reader = user("reader", Role.USER);
        AppUser reporter = user("reporter", Role.REPORTER);
        AppUser admin = user("admin", Role.ADMIN);
        assertEquals(401, call("GET", "/api/users/me", null, null).statusCode());
        assertEquals(403, call("GET", "/api/admin/users", null, token(reader)).statusCode());
        assertEquals(403, call("GET", "/api/admin/users", null, token(reporter)).statusCode());
        assertEquals(200, call("GET", "/api/admin/users", null, token(admin)).statusCode());
        assertEquals(403, call("GET", "/api/admin/ai/health", null, token(reader)).statusCode());
        // 503 proves reporter/admin pass authorization, while our test Ollama is deliberately offline.
        assertEquals(503, call("GET", "/api/admin/ai/health", null, token(reporter)).statusCode());
        assertEquals(503, call("GET", "/api/admin/ai/health", null, token(admin)).statusCode());
        assertEquals(403, call("PATCH", "/api/admin/users/" + reader.getId() + "/access",
                Map.of("role", "ADMIN", "enabled", true), token(reader)).statusCode());
        assertEquals(400, call("GET", "/api/admin/users?size=1000", null, token(admin)).statusCode());
    }

    @Test @Order(5)
    void disablingAndRoleChangesInvalidateExistingTokensImmediately() throws Exception {
        AppUser admin = user("admin", Role.ADMIN);
        AppUser reader = user("reader", Role.USER);
        String old = token(reader), adminToken = token(admin);
        String route = "/api/admin/users/" + reader.getId() + "/access";
        assertEquals(200, call("PATCH", route, Map.of("role", "REPORTER", "enabled", true), adminToken).statusCode());
        assertEquals(401, call("GET", "/api/users/me", null, old).statusCode());
        String reporterToken = token(reader);
        assertEquals(200, call("GET", "/api/users/me", null, reporterToken).statusCode());
        assertEquals(200, call("PATCH", route, Map.of("role", "REPORTER", "enabled", false), adminToken).statusCode());
        assertEquals(401, call("GET", "/api/users/me", null, reporterToken).statusCode());
        var disabledLogin = call("POST", "/api/auth/login", login("reader", PASSWORD), null);
        var wrongLogin = call("POST", "/api/auth/login", login("missing", PASSWORD), null);
        assertEquals(401, disabledLogin.statusCode());
        assertEquals(disabledLogin.body(), wrongLogin.body());
        assertEquals(200, call("PATCH", route, Map.of("role", "USER", "enabled", true), adminToken).statusCode());
        assertEquals(401, call("GET", "/api/users/me", null, reporterToken).statusCode());
    }

    @Test @Order(6)
    void logoutRevokesAllPreviouslyIssuedTokens() throws Exception {
        AppUser reader = user("reader", Role.USER);
        String first = token(reader), second = token(reader);
        assertEquals(204, call("POST", "/api/auth/logout", null, first).statusCode());
        assertEquals(401, call("GET", "/api/users/me", null, first).statusCode());
        assertEquals(401, call("GET", "/api/users/me", null, second).statusCode());
        assertEquals(200, call("POST", "/api/auth/login", login("reader", PASSWORD), null).statusCode());
    }

    @Test @Order(7)
    void passwordChangesRequireCurrentPasswordAndRevokeTokens() throws Exception {
        AppUser reader = user("reader", Role.USER);
        String token = token(reader), changed = "new-password-secure";
        assertEquals(401, call("POST", "/api/users/me/password", Map.of("currentPassword", "wrong-password",
                "newPassword", changed), token).statusCode());
        assertEquals(200, call("GET", "/api/users/me", null, token).statusCode());
        assertEquals(204, call("POST", "/api/users/me/password", Map.of("currentPassword", PASSWORD,
                "newPassword", changed), token).statusCode());
        assertEquals(401, call("GET", "/api/users/me", null, token).statusCode());
        assertEquals(401, call("POST", "/api/auth/login", login("reader", PASSWORD), null).statusCode());
        assertEquals(200, call("POST", "/api/auth/login", login("reader", changed), null).statusCode());
    }

    String signed(AppUser user, String issuer, String audience, Instant expiresAt, boolean includeExpiry) {
        var builder = JwtClaimsSet.builder().subject(user.getId().toString()).issuer(issuer)
                .audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(300)).claim("ver", 0)
                .claim("role", "ADMIN");
        if (includeExpiry) builder.expiresAt(expiresAt);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build())).getTokenValue();
    }

    @Test @Order(8)
    void rejectsExpiredTamperedWrongIssuerAudienceAndMissingExpiryTokens() throws Exception {
        AppUser reader = user("reader", Role.USER);
        String valid = token(reader);
        for (String invalid : List.of(valid.substring(0, valid.lastIndexOf('.') + 1) + "invalid-signature",
                signed(reader, "news-blog", "news-blog-api", Instant.now().minusSeconds(120), true),
                signed(reader, "wrong", "news-blog-api", Instant.now().plusSeconds(100), true),
                signed(reader, "news-blog", "wrong", Instant.now().plusSeconds(100), true),
                signed(reader, "news-blog", "news-blog-api", Instant.now(), false))) {
            assertEquals(401, call("GET", "/api/users/me", null, invalid).statusCode());
        }
        // Even signed role claims cannot override the current database role.
        assertEquals(403, call("GET", "/api/admin/users", null,
                signed(reader, "news-blog", "news-blog-api", Instant.now().plusSeconds(100), true)).statusCode());
    }

    @Test @Order(9)
    void protectsLastAdminAndBootstrapsOnlyOnceWithoutOverwritingPasswords() throws Exception {
        service.bootstrap("admin", "admin@example.com", PASSWORD);
        AppUser admin = users.findByUsername("admin").orElseThrow();
        String hash = admin.getPasswordHash();
        service.bootstrap("another", "another@example.com", "another-secure-password");
        assertEquals(1, users.countByRoleAndEnabledTrue(Role.ADMIN));
        assertEquals(hash, users.findById(admin.getId()).orElseThrow().getPasswordHash());
        assertEquals(409, call("PATCH", "/api/admin/users/" + admin.getId() + "/access",
                Map.of("role", "USER", "enabled", true), token(admin)).statusCode());
        assertEquals(409, call("PATCH", "/api/admin/users/" + admin.getId() + "/access",
                Map.of("role", "ADMIN", "enabled", false), token(admin)).statusCode());
    }

    @Test @Order(10)
    void servesSimplePagesWithCspAndRejectsOversizedBodies() throws Exception {
        for (String path : List.of("/", "/login", "/register")) {
            var response = call("GET", path, null, null);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("auth-form"));
            assertFalse(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("unsafe-inline"));
        }
        assertEquals(200, call("GET", "/auth.js", null, null).statusCode());
        assertEquals(413, call("POST", "/api/auth/login", Map.of("username", "x".repeat(33000)), null).statusCode());
        var basic = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users/me"))
                .header("Authorization", "Basic YWktdGVzdDpsb2NhbC10ZXN0").GET().build();
        assertEquals(401, client.send(basic, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test @Order(100)
    void rateLimitsRepeatedLoginAttempts() throws Exception {
        boolean limited = false;
        for (int i = 0; i < 21; i++) {
            var response = call("POST", "/api/auth/login", login("missing", "bad"), null);
            if (response.statusCode() == 429) {
                assertEquals("RATE_LIMITED", json(response).path("code").asText());
                assertTrue(response.headers().firstValue("Retry-After").isPresent());
                limited = true;
                break;
            }
            assertEquals(401, response.statusCode());
        }
        assertTrue(limited);
    }
}
