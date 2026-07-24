package com.training.apigatewayservice.auth.controller;

import com.training.apigatewayservice.auth.dto.LoginRequest;
import com.training.apigatewayservice.auth.dto.LoginResponse;
import com.training.apigatewayservice.auth.dto.MessageResponse;
import com.training.apigatewayservice.auth.dto.RegisterRequest;
import com.training.apigatewayservice.auth.dto.SetPasswordRequest;
import com.training.apigatewayservice.auth.dto.VerifyOtpRequest;
import com.training.apigatewayservice.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Mono<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.getEmail());
    }

    @PostMapping("/verify-otp")
    public Mono<MessageResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return authService.verifyOtp(request.getEmail(), request.getOtp());
    }

    @PostMapping("/set-password")
    public Mono<MessageResponse> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        return authService.setPassword(request.getEmail(), request.getPassword(), request.getConfirmPassword());
    }

    @PostMapping("/login")
    public Mono<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.getEmail(), request.getPassword());
    }
}
