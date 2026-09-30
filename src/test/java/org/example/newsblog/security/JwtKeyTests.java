package org.example.newsblog.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class JwtKeyTests {
    private final SecurityConfiguration configuration = new SecurityConfiguration();
    @Test void requiresStrongExternalSecretOutsideLocalTestProfiles() {
        var environment = new MockEnvironment();
        assertThrows(IllegalStateException.class, () -> configuration.jwtSecret("", environment));
        assertThrows(IllegalStateException.class, () -> configuration.jwtSecret("not-base64!", environment));
        assertThrows(IllegalStateException.class, () -> configuration.jwtSecret(
                Base64.getEncoder().encodeToString(new byte[16]), environment));
        assertEquals(32, configuration.jwtSecret(Base64.getEncoder().encodeToString(new byte[32]), environment)
                .getEncoded().length);
    }
    @Test void localTemporaryKeysAreRandomAndNotSharedBetweenStarts() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("ai-local");
        byte[] first = configuration.jwtSecret("", environment).getEncoded();
        byte[] second = configuration.jwtSecret("", environment).getEncoded();
        assertEquals(32, first.length);
        assertFalse(java.util.Arrays.equals(first, second));
    }
}
