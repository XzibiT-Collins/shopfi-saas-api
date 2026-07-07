package com.example.perfume_budget.notification;

public interface MailTransport {
    void send(MailMessage message);
}
