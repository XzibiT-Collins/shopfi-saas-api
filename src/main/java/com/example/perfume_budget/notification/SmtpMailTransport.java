package com.example.perfume_budget.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class SmtpMailTransport implements MailTransport {
    private final JavaMailSender javaMailSender;
    private final String fromAddress;
    private final String fromName;

    public SmtpMailTransport(JavaMailSender javaMailSender, String fromAddress, String fromName) {
        this.javaMailSender = javaMailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void send(MailMessage message) {
        try {
            MimeMessage mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, StandardCharsets.UTF_8.name());
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            setFrom(helper);
            helper.setText(message.html(), true);
            javaMailSender.send(mimeMessage);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailDeliveryException("Failed to send email via SMTP", e);
        }
    }

    private void setFrom(MimeMessageHelper helper) throws MessagingException, UnsupportedEncodingException {
        if (fromName == null || fromName.isBlank()) {
            helper.setFrom(fromAddress);
            return;
        }
        helper.setFrom(fromAddress, fromName);
    }
}
