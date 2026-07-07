package com.example.perfume_budget.notification;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class ResendMailTransportTest {

    @Test
    void send_postsExpectedPayloadToResendApi() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);

        server.expect(ExpectedCount.once(), requestTo("https://api.resend.com/emails"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer resend-api-key"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {
                          "from": "Perfume Budget <sender@example.com>",
                          "to": ["user@example.com"],
                          "subject": "Hello",
                          "html": "<strong>hello</strong>"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"email_123\"}", MediaType.APPLICATION_JSON));

        ResendMailTransport transport = new ResendMailTransport(
                restTemplate,
                "resend-api-key",
                "https://api.resend.com",
                "sender@example.com",
                "Perfume Budget"
        );

        transport.send(new MailMessage("ignored@example.com", "user@example.com", "Hello", "<strong>hello</strong>"));

        server.verify();
    }
}
