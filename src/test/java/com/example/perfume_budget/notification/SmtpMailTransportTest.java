package com.example.perfume_budget.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpMailTransportTest {

    @Test
    void send_buildsMimeMessageWithConfiguredFromAndHtmlBody() throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        SmtpMailTransport transport = new SmtpMailTransport(javaMailSender, "sender@example.com", "Perfume Budget");

        transport.send(new MailMessage("ignored@example.com", "user@example.com", "Hello", "<strong>hello</strong>"));

        verify(javaMailSender).send(any(MimeMessage.class));
        assertEquals("Hello", mimeMessage.getSubject());
        assertEquals("sender@example.com", ((InternetAddress) mimeMessage.getFrom()[0]).getAddress());
        assertEquals("Perfume Budget", ((InternetAddress) mimeMessage.getFrom()[0]).getPersonal());
    }
}
