package org.example.newsblog.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 40)
    private String username;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {}

    public AppUser(String username, String email, String passwordHash, Role role) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public long getTokenVersion() { return tokenVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void updateAccess(Role role, boolean enabled) {
        this.role = role;
        this.enabled = enabled;
        revokeTokens();
    }
    public void changePassword(String hash) { this.passwordHash = hash; revokeTokens(); }
    public void revokeTokens() { tokenVersion++; }
}
