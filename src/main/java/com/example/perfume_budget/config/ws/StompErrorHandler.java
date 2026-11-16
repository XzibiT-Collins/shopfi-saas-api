package com.example.perfume_budget.config.ws;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;

/**
 * ERROR frames for refused client frames (SEC-03c): a {@link StompAccessDeniedException} anywhere in the cause chain
 * yields its own short reason; anything else a generic text, so no internal exception detail reaches the client.
 */
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        String reason = "Bad request";
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof StompAccessDeniedException denied) {
                reason = denied.getMessage();
                break;
            }
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(reason);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(reason.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }
}
