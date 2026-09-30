package org.example.newsblog.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.example.newsblog.user.UserService;
import org.example.newsblog.user.UserView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final UserService users;
    private final JwtTokens tokens;
    AuthController(UserService users, JwtTokens tokens) { this.users = users; this.tokens = tokens; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserView register(@Valid @RequestBody RegisterRequest request) {
        return UserView.of(users.register(request.username(), request.email(), request.password()));
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var user = users.authenticate(request.username(), request.password());
        var token = tokens.issue(user);
        return new LoginResponse(token.accessToken(), token.tokenType(), token.expiresIn(), UserView.of(user));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) { users.logout(UUID.fromString(jwt.getSubject())); }

    record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,40}") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {}
    record LoginRequest(@NotBlank @Size(max = 40) String username,
                        @NotBlank @Size(max = 72) String password) {}
    record LoginResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}
}
