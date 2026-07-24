package com.training.apigatewayservice.auth.entity;

public enum UserStatus {
    // Registered, OTP emailed, waiting for the caller to submit it.
    PENDING_OTP,
    // OTP confirmed; waiting for the caller to set a password.
    OTP_VERIFIED,
    // Password set; can log in.
    ACTIVE
}
