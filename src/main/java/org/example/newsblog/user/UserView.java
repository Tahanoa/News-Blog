package org.example.newsblog.user;

import java.time.Instant;
import java.util.UUID;

public record UserView(UUID id, String username, String email, Role role, boolean enabled, Instant createdAt) {
    public static UserView of(AppUser user) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getRole(),
                user.isEnabled(), user.getCreatedAt());
    }
}
