package com.example.perfume_budget.config.ws;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** SEC-03c review F6 / R27: ERROR frames carry only our own refusal reason, never exception text. */
class StompErrorHandlerTest {

    private final StompErrorHandler handler = new StompErrorHandler();

    private static Message<byte[]> clientFrame() {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        a.setSessionId("s1");
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private static void assertReason(Message<byte[]> error, String reason) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(error);
        assertThat(h.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(h.getMessage()).isEqualTo(reason);
        assertThat(new String(error.getPayload(), StandardCharsets.UTF_8)).isEqualTo(reason);
    }

    @Test
    void anAccessDenialAnywhereInTheCauseChainYieldsItsOwnReason() {
        StompAccessDeniedException denied = new StompAccessDeniedException("Destination not allowed");
        assertReason(handler.handleClientMessageProcessingError(clientFrame(), denied), "Destination not allowed");
        assertReason(handler.handleClientMessageProcessingError(clientFrame(),
                new MessageDeliveryException(clientFrame(), "wrapped", denied)), "Destination not allowed");
    }

    @Test
    void anythingElseYieldsTheFixedGenericReasonWithoutExceptionDetail() {
        for (Throwable ex : new Throwable[]{
                new IllegalStateException("jdbc:postgresql://db.internal/tenant_acme secret detail"),
                new MessageDeliveryException(clientFrame(), "delivery failed: tenant_acme", new RuntimeException("stack detail")),
                new NullPointerException()}) {
            Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame(), ex);
            assertReason(error, "Bad request");
            assertThat(new String(error.getPayload(), StandardCharsets.UTF_8)).doesNotContain("tenant_acme", "detail", "Exception");
        }
    }
}
