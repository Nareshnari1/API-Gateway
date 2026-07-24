package com.training.apigatewayservice.auth.config;

import com.training.apigatewayservice.auth.entity.User;
import com.training.apigatewayservice.auth.entity.UserRole;
import com.training.apigatewayservice.auth.entity.UserStatus;
import com.training.apigatewayservice.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final String adminEmail;
    private final String adminPassword;

    public AdminSeeder(UserRepository userRepository,
                        @Value("${app.admin.email}") String adminEmail,
                        @Value("${app.admin.password}") String adminPassword) {
        this.userRepository = userRepository;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmailIgnoreCase(adminEmail)) {
            return;
        }
        User admin = new User(adminEmail, UserRole.ADMIN, UserStatus.ACTIVE);
        admin.setPasswordHash(new BCryptPasswordEncoder().encode(adminPassword));
        userRepository.save(admin);
        log.info("Seeded default admin account: {}", adminEmail);
    }
}
