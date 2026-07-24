package com.training.apigatewayservice.auth.exception;

public class InvalidRegistrationStateException extends RuntimeException {
    public InvalidRegistrationStateException(String message) {
        super(message);
    }
}
