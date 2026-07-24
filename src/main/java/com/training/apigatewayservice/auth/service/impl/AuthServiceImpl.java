package com.training.apigatewayservice.auth.service.impl;

import com.training.apigatewayservice.auth.client.NotificationClient;
import com.training.apigatewayservice.auth.dto.LoginResponse;
import com.training.apigatewayservice.auth.dto.MessageResponse;
import com.training.apigatewayservice.auth.entity.User;
import com.training.apigatewayservice.auth.entity.UserRole;
import com.training.apigatewayservice.auth.entity.UserStatus;
import com.training.apigatewayservice.auth.exception.EmailAlreadyRegisteredException;
import com.training.apigatewayservice.auth.exception.InvalidCredentialsException;
import com.training.apigatewayservice.auth.exception.InvalidOtpException;
import com.training.apigatewayservice.auth.exception.InvalidRegistrationStateException;
import com.training.apigatewayservice.auth.exception.PasswordMismatchException;
import com.training.apigatewayservice.auth.exception.UserNotFoundException;
import com.training.apigatewayservice.auth.repository.UserRepository;
import com.training.apigatewayservice.auth.service.AuthService;
import com.training.apigatewayservice.auth.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * JPA and the Feign-backed notification call are both blocking, so every
 * public method here runs its actual work on a bounded-elastic thread via
 * Mono.fromCallable - calling them directly on a WebFlux/Netty event-loop
 * thread trips Reactor's blocking-call guard (BlockingOperatorError) as soon
 * as the load-balanced Feign call resolves an instance.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int OTP_VALIDITY_MINUTES = 10;

    private final UserRepository userRepository;
    private final NotificationClient notificationClient;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    // Dev-only: when true, the OTP is returned in the /register response so the UI can
    // show it without real email delivery (Docker uses dummy SMTP). Disable in prod.
    @Value("${app.otp.dev-return:true}")
    private boolean devReturnOtp;

    public AuthServiceImpl(UserRepository userRepository, NotificationClient notificationClient,
                            JwtService jwtService) {
        this.userRepository = userRepository;
        this.notificationClient = notificationClient;
        this.jwtService = jwtService;
    }

    @Override
    public Mono<MessageResponse> register(String email) {
        return Mono.fromCallable(() -> registerBlocking(email)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<MessageResponse> verifyOtp(String email, String otp) {
        return Mono.fromCallable(() -> verifyOtpBlocking(email, otp)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<MessageResponse> setPassword(String email, String password, String confirmPassword) {
        return Mono.fromCallable(() -> setPasswordBlocking(email, password, confirmPassword))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<LoginResponse> login(String email, String password) {
        return Mono.fromCallable(() -> loginBlocking(email, password)).subscribeOn(Schedulers.boundedElastic());
    }

    protected MessageResponse registerBlocking(String email) {
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user != null && user.getStatus() == UserStatus.ACTIVE) {
            throw new EmailAlreadyRegisteredException("An account with this email already exists");
        }
        if (user == null) {
            user = new User(email, UserRole.USER, UserStatus.PENDING_OTP);
        } else {
            user.setStatus(UserStatus.PENDING_OTP);
        }

        String otp = generateOtp();
        user.setOtp(otp, LocalDateTime.now().plusMinutes(OTP_VALIDITY_MINUTES));
        userRepository.save(user);

        // Dev convenience: log the OTP so registration is testable locally when real
        // email delivery isn't configured (Docker uses dummy SMTP). Disable in prod.
        log.info("[DEV] OTP for {} is {} (valid {} minutes)", email, otp, OTP_VALIDITY_MINUTES);
        sendOtpEmail(email, otp);

        String message = "OTP sent to " + email;
        if (devReturnOtp) {
            // Dev-only: surface the code in the response so the UI can show it without
            // real email delivery. Set app.otp.dev-return=false in production.
            message += " — dev code: " + otp;
        }
        return new MessageResponse(message);
    }

    protected MessageResponse verifyOtpBlocking(String email, String otp) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UserNotFoundException("No registration found for " + email));

        if (user.getStatus() != UserStatus.PENDING_OTP) {
            throw new InvalidRegistrationStateException("No pending OTP for this email");
        }
        if (user.getOtpCode() == null || !user.getOtpCode().equals(otp)) {
            throw new InvalidOtpException("Invalid OTP");
        }
        if (user.getOtpExpiresAt() == null || user.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidOtpException("OTP has expired, please register again");
        }

        user.setStatus(UserStatus.OTP_VERIFIED);
        user.clearOtp();
        userRepository.save(user);
        return new MessageResponse("OTP verified. You can now set your password.");
    }

    protected MessageResponse setPasswordBlocking(String email, String password, String confirmPassword) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UserNotFoundException("No registration found for " + email));

        if (user.getStatus() != UserStatus.OTP_VERIFIED) {
            throw new InvalidRegistrationStateException("Please verify your OTP before setting a password");
        }
        if (!password.equals(confirmPassword)) {
            throw new PasswordMismatchException("password and confirmPassword do not match");
        }

        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        return new MessageResponse("Registration complete. You can now log in.");
    }

    protected LoginResponse loginBlocking(String email, String password) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidRegistrationStateException(
                    "Please complete registration (verify OTP and set your password) before logging in");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getEmail(), user.getRole(), user.getId());
        return new LoginResponse(token, user.getEmail(), user.getRole(), user.getId());
    }

    private String generateOtp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private void sendOtpEmail(String email, String otp) {
        try {
            notificationClient.sendEmail(new NotificationClient.EmailNotificationRequest(
                    email,
                    "Your verification code",
                    "Your OTP is " + otp + ". It expires in " + OTP_VALIDITY_MINUTES + " minutes."));
        } catch (Exception ex) {
            // Registration itself already succeeded (OTP is stored); the caller can
            // still complete verification once notification-service is reachable
            // again, so a delivery failure here shouldn't fail the whole request.
            log.warn("Failed to send OTP email to {}: {}", email, ex.getMessage());
        }
    }
}
