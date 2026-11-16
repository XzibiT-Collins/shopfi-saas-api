package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.ResetPasswordEvent;
import com.example.perfume_budget.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** EVT-01: after commit only (at once outside a transaction), on the dedicated mail executor. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForgotPasswordHandler {
    private final EmailService emailService;
    private final MailDispatcher mailDispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handlePasswordResetEvent(ResetPasswordEvent event){
        mailDispatcher.submit("password reset e-mail", MailDispatcher.reference(event), () -> {
            log.info("Password reset e-mail requested on tenant {}", event.tenantSlug()); // never the address (F3)
            emailService.sendForgotPasswordEmail(event.email(), event.fullName(), event.token(), event.tenantSlug());
        });
    }
}
