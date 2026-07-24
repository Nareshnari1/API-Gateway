package com.training.apigatewayservice.auth.service;

import com.training.apigatewayservice.auth.dto.LoginResponse;
import com.training.apigatewayservice.auth.dto.MessageResponse;
import reactor.core.publisher.Mono;

public interface AuthService {

    Mono<MessageResponse> register(String email);

    Mono<MessageResponse> verifyOtp(String email, String otp);

    Mono<MessageResponse> setPassword(String email, String password, String confirmPassword);

    Mono<LoginResponse> login(String email, String password);
}
