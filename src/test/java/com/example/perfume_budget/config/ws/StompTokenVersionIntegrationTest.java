package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API-RD-08: STOMP CONNECT refuses a token minted before a password change (token_version bump) and accepts a current one. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.platform-domains=shopfi.example",
        "platform.cors.allowed-origins=https://console.example.com",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
class StompTokenVersionIntegrationTest extends AbstractPostgresIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestTenants tenants;
    @Autowired UserRepository users;
    @Autowired JWTUtil jwtUtil;
    @Autowired JdbcTemplate jdbc;

    StompSession connect(String bearer) throws Exception {
        var client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.setOrigin("http://acme.shopfi.test");
        StompHeaders c = new StompHeaders();
        c.add("Authorization", "Bearer " + bearer);
        return client.connectAsync("http://127.0.0.1:" + port + "/ws", h, c, new StompSessionHandlerAdapter() { }).get(15, TimeUnit.SECONDS);
    }

    @Test
    void connectRefusesAPreBumpToken_acceptsACurrentOne() throws Exception {
        TestTenant acme = tenants.acme();
        String email = "stomp-tv@acme.test";
        tenants.runAs(acme, () -> { if (users.findByEmail(email).isEmpty()) users.save(User.builder().fullName("S").email(email)
                .password("{noop}x").roles(UserRole.ADMIN).isActive(true).emailVerified(true).authProvider(AuthProvider.LOCAL).build()); });
        try {
            String oldTok = tenants.runAs(acme, () -> { User u = users.findByEmail(email).orElseThrow();
                return jwtUtil.generateAccessToken(u.getEmail(), u.getRole(), u.getId(), u.getFullName(), 0); });
            jdbc.update("UPDATE tenant_acme.users SET token_version = 1 WHERE email = ?", email);
            String curTok = tenants.runAs(acme, () -> { User u = users.findByEmail(email).orElseThrow();
                return jwtUtil.generateAccessToken(u.getEmail(), u.getRole(), u.getId(), u.getFullName(), u.getTokenVersion()); });
            assertThat(connect(curTok).isConnected()).isTrue();
            assertThatThrownBy(() -> connect(oldTok)).as("pre-bump token must not open a socket").isNotNull();
        } finally {
            jdbc.update("DELETE FROM tenant_acme.users WHERE email = ?", email);
        }
    }
}
