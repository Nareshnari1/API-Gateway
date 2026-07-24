package com.training.apigatewayservice.auth.dto;

import com.training.apigatewayservice.auth.entity.UserRole;

public class LoginResponse {

    private String token;
    private String email;
    private UserRole role;
    private Long customerId;

    public LoginResponse(String token, String email, UserRole role, Long customerId) {
        this.token = token;
        this.email = email;
        this.role = role;
        this.customerId = customerId;
    }

    public String getToken() {
        return token;
    }

    public String getEmail() {
        return email;
    }

    public UserRole getRole() {
        return role;
    }

    public Long getCustomerId() {
        return customerId;
    }
}
