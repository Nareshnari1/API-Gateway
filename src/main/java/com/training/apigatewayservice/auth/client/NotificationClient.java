package com.training.apigatewayservice.auth.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "notification-service")
public interface NotificationClient {

    record EmailNotificationRequest(String recipient, String subject, String message) {
    }

    @PostMapping("/api/v1/notifications/email")
    void sendEmail(@RequestBody EmailNotificationRequest request);
}
