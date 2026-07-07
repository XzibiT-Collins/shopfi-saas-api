package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OTPEmailSendHandler {
    private final EmailService emailService;

    @Async
    @EventListener
    public void handleUserRegistrationOtpEvent(UserRegistrationEvent event){
        log.info("Received UserLoginEvent for email: {}", event.email());
        emailService.sendOtpEmail(event.email(), event.otp());
    }

    @Async
    @EventListener
    public void handleLoginOtpEvent(LoginOtpEvent event) {
        log.info("Received LoginOtpEvent for email: {}", event.email());
        emailService.sendOtpEmail(event.email(), event.otp());
    }
}
