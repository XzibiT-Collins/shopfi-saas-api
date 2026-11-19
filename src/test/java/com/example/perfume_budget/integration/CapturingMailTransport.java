package com.example.perfume_budget.integration;

import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.notification.MailTransport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-scope mail transport: nothing is sent, every message is kept for GET /__test/mail. */
final class CapturingMailTransport implements MailTransport {

    final List<MailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(MailMessage message) {
        sent.add(message);
    }
}
