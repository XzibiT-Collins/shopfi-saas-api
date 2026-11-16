package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.LoginOtpEvent;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * EVT-01: OTP e-mails go out only for work that committed (a signup / resend whose transaction rolls back must not
 * mail a code that was never stored), once per committed transaction; published outside a transaction (the usual
 * case: the code lives in Redis) they are sent at once. Sent on the dedicated mail executor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OTPEmailSendHandler {
    private final EmailService emailService;
    private final MailDispatcher mailDispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleUserRegistrationOtpEvent(UserRegistrationEvent event){
        mailDispatcher.submit("registration OTP e-mail", MailDispatcher.reference(event), () -> {
            log.info("Received UserLoginEvent for email: {}", event.email());
            emailService.sendOtpEmail(event.email(), event.otp());
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleLoginOtpEvent(LoginOtpEvent event) {
        mailDispatcher.submit("login OTP e-mail", MailDispatcher.reference(event), () -> {
            log.info("Received LoginOtpEvent for email: {}", event.email());
            emailService.sendOtpEmail(event.email(), event.otp());
        });
    }
}
