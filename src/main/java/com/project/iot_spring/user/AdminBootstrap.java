package com.project.iot_spring.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator account when the users table is empty.
 * The password comes from configuration (env ADMIN_PASSWORD / app.admin.password) - never from the code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("No users in the database and app.admin.password is not set - nobody can log in. "
                    + "Set ADMIN_PASSWORD and restart.");
            return;
        }
        userRepository.save(new User(adminUsername, passwordEncoder.encode(adminPassword), User.ROLE_ADMIN));
        log.info("Created initial administrator account '{}'", adminUsername);
    }
}
