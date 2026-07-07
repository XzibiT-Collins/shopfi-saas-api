package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ForgotPasswordHandler {
    private final EmailService emailService;

    @Async
    @EventListener
    public void handlePasswordResetEvent(ResetPasswordEvent event){
        log.info("Received ForgotPasswordEvent for email: {}", event.email());
        emailService.sendForgotPasswordEmail(event.email(), event.fullName(), event.token());
    }
}
