package com.example.perfume_budget.config.ws;

/**
 * Thrown by {@link AuthChannelInterceptor} to refuse a STOMP frame (SEC-03c). Spring turns it into an ERROR frame and
 * closes the session; {@link StompErrorHandler} puts only the safe {@link #getMessage()} in that frame.
 */
public class StompAccessDeniedException extends RuntimeException {

    public StompAccessDeniedException(String message) {
        super(message);
    }
}
