package com.agrosense.backend.seed;

import com.agrosense.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sample data itself lives in /database/seed_demo.sql, which creates the demo user without a usable
 * password. With the "seed" profile this sets that user's password from SEED_USER_PASSWORD.
 */
@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class SeedAccountInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${agrosense.seed.email:demo@agrosense.co}")
    private String seedEmail;

    @Value("${agrosense.seed.password:}")
    private String seedPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seedPassword.isBlank()) {
            throw new IllegalStateException("The 'seed' profile requires SEED_USER_PASSWORD to be set");
        }
        userRepository.findByEmail(seedEmail).ifPresentOrElse(
                user -> {
                    user.setPasswordHash(passwordEncoder.encode(seedPassword));
                    log.info("Password set for seed user {}", seedEmail);
                },
                () -> log.warn("Seed user {} not found: was database/seed_demo.sql applied?", seedEmail));
    }
}
