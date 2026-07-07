package com.example.perfume_budget.config;

import com.example.perfume_budget.notification.MailTransport;
import com.example.perfume_budget.notification.ResendMailTransport;
import com.example.perfume_budget.notification.SmtpMailTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MailTransportConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MailTransportConfiguration.class)
            .withBean(JavaMailSender.class, () -> org.mockito.Mockito.mock(JavaMailSender.class))
            .withBean(RestTemplate.class, RestTemplate::new);

    @Test
    void loadsSmtpTransportByDefault() {
        contextRunner
                .withPropertyValues(
                        "notification.email.from-address=sender@example.com",
                        "notification.email.from-name=Perfume Budget"
                )
                .run(context -> {
                    MailTransport transport = context.getBean(MailTransport.class);
                    assertInstanceOf(SmtpMailTransport.class, transport);
                });
    }

    @Test
    void loadsResendTransportWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "notification.email.provider=resend",
                        "notification.email.from-address=sender@example.com",
                        "notification.email.from-name=Perfume Budget",
                        "notification.email.resend.api-key=resend-api-key"
                )
                .run(context -> {
                    MailTransport transport = context.getBean(MailTransport.class);
                    assertInstanceOf(ResendMailTransport.class, transport);
                });
    }
}
