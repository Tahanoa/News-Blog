package org.example.newsblog.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class UserController {
    private final UserService users;
    UserController(UserService users) { this.users = users; }

    @GetMapping("/api/users/me")
    UserView me(@AuthenticationPrincipal Jwt jwt) { return users.me(UUID.fromString(jwt.getSubject())); }

    @PostMapping("/api/users/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordRequest request) {
        users.changePassword(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword());
    }

    @GetMapping("/api/admin/users")
    UserPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = users.list(page, size);
        return new UserPage(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @PatchMapping("/api/admin/users/{id}/access")
    UserView access(@PathVariable UUID id, @Valid @RequestBody AccessRequest request) {
        return users.updateAccess(id, request.role(), request.enabled());
    }

    record PasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
                           @NotBlank @Size(min = 12, max = 72) String newPassword) {}
    record AccessRequest(@NotNull Role role, @NotNull Boolean enabled) {}
    record UserPage(List<UserView> users, int page, int size, long total) {}
}
