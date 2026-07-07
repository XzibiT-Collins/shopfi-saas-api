package com.example.perfume_budget.notification;

public record MailMessage(
        String from,
        String to,
        String subject,
        String html
) {
}
