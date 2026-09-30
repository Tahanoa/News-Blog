package org.example.newsblog.security;

import org.example.newsblog.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class AdminBootstrap implements ApplicationRunner {
    private final UserService users;
    private final String username, email, password;
    AdminBootstrap(UserService users, @Value("${security.bootstrap.username}") String username,
                   @Value("${security.bootstrap.email}") String email,
                   @Value("${security.bootstrap.password}") String password) {
        this.users = users; this.username = username; this.email = email; this.password = password;
    }
    @Override public void run(ApplicationArguments args) {
        if (username.isBlank() && email.isBlank() && password.isBlank()) return;
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Set all three ADMIN_USERNAME, ADMIN_EMAIL and ADMIN_PASSWORD values");
        }
        users.bootstrap(username, email, password);
    }
}
