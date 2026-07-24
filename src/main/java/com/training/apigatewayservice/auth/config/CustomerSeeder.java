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

/**
 * Seeds a default, already-ACTIVE customer account (mirrors AdminSeeder) so a
 * working USER login exists without going through the OTP registration flow -
 * useful for local dev/demo before a self-registration UI is built.
 */
@Component
public class CustomerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerSeeder.class);

    private final UserRepository userRepository;
    private final String customerEmail;
    private final String customerPassword;

    public CustomerSeeder(UserRepository userRepository,
                           @Value("${app.customer.email}") String customerEmail,
                           @Value("${app.customer.password}") String customerPassword) {
        this.userRepository = userRepository;
        this.customerEmail = customerEmail;
        this.customerPassword = customerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmailIgnoreCase(customerEmail)) {
            return;
        }
        User customer = new User(customerEmail, UserRole.USER, UserStatus.ACTIVE);
        customer.setPasswordHash(new BCryptPasswordEncoder().encode(customerPassword));
        userRepository.save(customer);
        log.info("Seeded default customer account: {}", customerEmail);
    }
}
