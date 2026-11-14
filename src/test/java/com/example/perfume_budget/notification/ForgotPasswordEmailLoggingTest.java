package com.example.perfume_budget.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import com.example.perfume_budget.support.TestLinkBuilders;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TENANT-11 AC4 (N3): the password reset URL/token must never be written to any log level. */
class ForgotPasswordEmailLoggingTest {

    @Test
    void forgotPasswordEmailNeverLogsTheResetTokenOrUrl() {
        MailTransport transport = mock(MailTransport.class);
        TemplateEngine engine = mock(TemplateEngine.class);
        when(engine.process(eq("forgot-password"), any(Context.class))).thenReturn("<p>reset</p>");
        EmailServiceImpl service = new EmailServiceImpl(transport, engine, mock(DeliveryDetailRepository.class),
                "admin@example.com", TestLinkBuilders.linkBuilder("shopfi.test", "https://frontend.example.com/reset-password"));
        String token = "SECRET-RESET-TOKEN-0123456789";

        Logger logger = (Logger) LoggerFactory.getLogger(EmailServiceImpl.class);
        Level old = logger.getLevel();
        logger.setLevel(Level.TRACE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.sendForgotPasswordEmail("user@example.com", "Jane", token, "acme");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(old);
        }
        verify(transport).send(any(MailMessage.class));
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage()).doesNotContain(token).doesNotContain("resetPasswordToken");
        }
    }
}
