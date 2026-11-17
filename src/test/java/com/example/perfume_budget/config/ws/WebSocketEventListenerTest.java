package com.example.perfume_budget.config.ws;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SEC-03c: only staff sessions are tracked, per tenant and per session id. */
class WebSocketEventListenerTest {

    private final AdminSessionManager sessions = new AdminSessionManager();
    private final WebSocketEventListener listener = new WebSocketEventListener(sessions);

    private static StompTenantAuthentication user(String tenant, String email, String role) {
        return new StompTenantAuthentication(tenant, email, email, List.of(new SimpleGrantedAuthority(role)));
    }

    private static Message<byte[]> connectAck(String sessionId, Principal user) {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.CONNECTED);
        a.setSessionId(sessionId);
        a.setUser(user);
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private void connected(String sessionId, Principal user) {
        listener.handleWebSocketConnectListener(new SessionConnectedEvent(this, connectAck(sessionId, user), user));
    }

    private void disconnected(String sessionId, Principal user) {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        a.setSessionId(sessionId);
        listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(this,
                MessageBuilder.createMessage(new byte[0], a.getMessageHeaders()), sessionId, CloseStatus.NORMAL, user));
    }

    @Test
    void staffAreTrackedPerTenantAndSession() {
        StompTenantAuthentication admin = user("acme", "a@acme.test", "ROLE_ADMIN");
        StompTenantAuthentication desk = user("acme", "d@acme.test", "ROLE_FRONT_DESK");
        connected("s1", admin);
        connected("s2", desk);
        assertThat(sessions.getConnectedAdmins("acme")).containsExactlyInAnyOrder("a@acme.test", "d@acme.test");
        assertThat(sessions.getConnectedAdmins("bravo")).isEmpty();
        disconnected("s1", admin);
        assertThat(sessions.getConnectedAdmins("acme")).containsExactly("d@acme.test");
    }

    @Test
    void customersAndForeignPrincipalsAreNeverTracked() {
        connected("s1", user("acme", "c@acme.test", "ROLE_CUSTOMER"));
        connected("s2", new UsernamePasswordAuthenticationToken("x@acme.test", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        connected("s3", null);
        assertThat(sessions.getConnectedAdmins("acme")).isEmpty();
        disconnected("s3", null); // no principal: ignored
    }
}
