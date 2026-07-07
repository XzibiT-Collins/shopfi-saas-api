package com.example.perfume_budget.config;

import com.example.perfume_budget.notification.MailTransport;
import com.example.perfume_budget.notification.ResendMailTransport;
import com.example.perfume_budget.notification.SmtpMailTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

@Configuration
public class MailTransportConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "notification.email", name = "provider", havingValue = "smtp", matchIfMissing = true)
    public MailTransport smtpMailTransport(
            JavaMailSender javaMailSender,
            @Value("${notification.email.from-address}") String fromAddress,
            @Value("${notification.email.from-name}") String fromName
    ) {
        return new SmtpMailTransport(javaMailSender, fromAddress, fromName);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.email", name = "provider", havingValue = "resend")
    public MailTransport resendMailTransport(
            RestTemplate restTemplate,
            @Value("${notification.email.resend.api-key:}") String apiKey,
            @Value("${notification.email.resend.base-url:https://api.resend.com}") String baseUrl,
            @Value("${notification.email.from-address:${spring.mail.username}}") String fromAddress,
            @Value("${notification.email.from-name:Perfume Budget}") String fromName
    ) {
        return new ResendMailTransport(restTemplate, apiKey, baseUrl, fromAddress, fromName);
    }
}
