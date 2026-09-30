package org.example.newsblog.user;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.example.newsblog.security.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final SecurityLockRepository lock;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public UserService(UserRepository users, SecurityLockRepository lock, PasswordEncoder passwords) {
        this.users = users;
        this.lock = lock;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AppUser register(String username, String email, String password) {
        validatePassword(password);
        username = normalize(username);
        email = normalize(email);
        if (users.existsByUsernameOrEmail(username, email)) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_EXISTS", "Username or email is already registered.");
        }
        return users.saveAndFlush(new AppUser(username, email, passwords.encode(password), Role.USER));
    }

    @Transactional(readOnly = true)
    public AppUser authenticate(String username, String password) {
        AppUser user = users.findByUsername(normalize(username)).orElse(null);
        boolean validLength = password.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwords.matches(validLength ? password : "invalid-password-length",
                user == null ? dummyHash : user.getPasswordHash());
        if (!validLength || !matches || user == null || !user.isEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials.");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public UserView me(UUID id) { return UserView.of(require(id)); }

    @Transactional(readOnly = true)
    public Page<UserView> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Page size must be between 1 and 100.");
        }
        return users.findAll(PageRequest.of(page, size, Sort.by("createdAt").descending())).map(UserView::of);
    }

    @Transactional
    public UserView updateAccess(UUID id, Role role, boolean enabled) {
        lock.acquire();
        AppUser user = users.findForUpdate(id).orElseThrow(this::notFound);
        if (user.getRole() == Role.ADMIN && user.isEnabled() && (role != Role.ADMIN || !enabled)
                && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN", "The last enabled administrator cannot be disabled or demoted.");
        }
        user.updateAccess(role, enabled);
        return UserView.of(user);
    }

    @Transactional
    public void logout(UUID id) { users.findForUpdate(id).orElseThrow(this::notFound).revokeTokens(); }

    @Transactional
    public void changePassword(UUID id, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        AppUser user = users.findForUpdate(id).orElseThrow(this::notFound);
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials.");
        }
        user.changePassword(passwords.encode(newPassword));
    }

    @Transactional
    public void bootstrap(String username, String email, String password) {
        lock.acquire();
        if (users.countByRoleAndEnabledTrue(Role.ADMIN) != 0) return;
        validatePassword(password);
        if (!username.matches("[a-zA-Z0-9_.-]{3,40}") || email.length() > 254
                || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalStateException("Invalid bootstrap administrator username or email");
        }
        if (users.existsByUsernameOrEmail(normalize(username), normalize(email))) {
            throw new IllegalStateException("Bootstrap administrator conflicts with an existing account");
        }
        users.saveAndFlush(new AppUser(normalize(username), normalize(email), passwords.encode(password), Role.ADMIN));
    }

    private AppUser require(UUID id) { return users.findById(id).orElseThrow(this::notFound); }
    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found.");
    }
    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
                    "Password must contain at least 12 characters and at most 72 UTF-8 bytes.");
        }
    }
    private static String normalize(String value) { return value.strip().toLowerCase(Locale.ROOT); }
}
