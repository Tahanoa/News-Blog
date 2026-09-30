package org.example.newsblog.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.example.newsblog.user.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtTokens {
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final Duration ttl;
    public JwtTokens(JwtEncoder encoder, @Value("${security.jwt.issuer}") String issuer,
                     @Value("${security.jwt.audience}") String audience,
                     @Value("${security.jwt.access-ttl}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("JWT lifetime must be positive and no longer than one hour");
        }
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = ttl;
    }
    public Token issue(AppUser user) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(user.getId().toString()).issuedAt(now).notBefore(now).expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString()).claim("ver", user.getTokenVersion()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return new Token(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
                "Bearer", ttl.toSeconds());
    }
    public record Token(String accessToken, String tokenType, long expiresIn) {}
}
