package org.example.newsblog.security;

import java.util.List;
import java.util.UUID;
import org.example.newsblog.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
class DatabaseJwtAuthentication implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserRepository users;
    DatabaseJwtAuthentication(UserRepository users) { this.users = users; }
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID id;
        try { id = UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) { throw invalid(); }
        var user = users.findById(id).orElseThrow(this::invalid);
        Object version = jwt.getClaims().get("ver");
        if (!user.isEnabled() || !(version instanceof Number number)
                || number.longValue() != user.getTokenVersion()) throw invalid();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())), id.toString());
    }
    private OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
