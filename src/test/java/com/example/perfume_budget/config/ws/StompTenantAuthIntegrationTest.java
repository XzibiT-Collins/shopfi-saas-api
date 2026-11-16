package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * SEC-03c / WS-01 end to end: a REAL SockJS + STOMP client against the app on a random port, two tenants, and the same
 * staff e-mail in both (the worst case for user-destination routing). Same test properties as
 * EncodedPathRawSocketIntegrationTest so the two share one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.platform-domains=shopfi.example",
        "platform.cors.allowed-origins=https://console.example.com",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
class StompTenantAuthIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SHARED_STAFF = "ws-staff@shared.test";
    private static final String ACME_CUSTOMER = "ws-customer@acme.test";
    private static final String QUEUE = "/user/queue/notifications";

    @LocalServerPort int port;
    @Autowired TestTenants tenants;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository users;
    @Autowired JWTUtil jwtUtil;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notificationService;
    @Autowired SimpUserRegistry userRegistry;
    @Autowired AdminSessionManager adminSessions;
    @Autowired TenantOriginPolicy originPolicy;
    @Value("${application.security.jwt.secret-key}") String jwtSecret;

    private TestTenant acme;
    private TestTenant bravo;
    private WebSocketStompClient client;
    private final List<StompSession> open = new ArrayList<>();

    @BeforeEach
    void setUp() {
        acme = tenants.acme();
        bravo = tenants.bravo();
        originPolicy.invalidateAll();
        user(acme, SHARED_STAFF, UserRole.ADMIN);
        user(bravo, SHARED_STAFF, UserRole.ADMIN);
        user(acme, ACME_CUSTOMER, UserRole.CUSTOMER);
        client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        client.setMessageConverter(new RawBytes());
    }

    @AfterEach
    void tearDown() {
        for (StompSession s : open) {
            if (s.isConnected()) {
                s.disconnect();
            }
        }
        client.stop();
        for (String schema : List.of(acme.schemaName(), bravo.schemaName())) {
            jdbc.update("DELETE FROM " + schema + ".notification_recipients WHERE notification_id IN "
                    + "(SELECT id FROM " + schema + ".notifications WHERE reference_id LIKE 'WS-%')");
            jdbc.update("DELETE FROM " + schema + ".notifications WHERE reference_id LIKE 'WS-%'");
            jdbc.update("DELETE FROM " + schema + ".users WHERE email IN (?, ?)", SHARED_STAFF, ACME_CUSTOMER);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private void user(TestTenant tenant, String email, UserRole role) {
        tenants.runAs(tenant, () -> {
            if (users.findByEmail(email).isEmpty()) {
                users.save(User.builder().fullName("WS " + role).email(email).password("{noop}unused")
                        .roles(role).isActive(true).emailVerified(true).authProvider(AuthProvider.LOCAL).build());
            }
        });
    }

    /** A real access token, minted exactly like login does: with the tenant bound, so the claim is that tenant. */
    private String token(TestTenant tenant, String email) {
        return tenants.runAs(tenant, () -> {
            User u = users.findByEmail(email).orElseThrow();
            return jwtUtil.generateAccessToken(u);
        });
    }

    private String expiredToken(TestTenant tenant, String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject(email).claims(Map.of("tenant", tenant.slug(), "role", "ADMIN"))
                .issuedAt(new Date(now - 7_200_000)).expiration(new Date(now - 60_000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).compact();
    }

    private static String origin(TestTenant tenant) {
        return "http://" + tenant.slug() + ".shopfi.test";
    }

    /** Hands the raw frame body to the test, whatever its content type. */
    static final class RawBytes implements MessageConverter {
        @Override public Object fromMessage(Message<?> message, Class<?> targetClass) { return message.getPayload(); }
        @Override public Message<?> toMessage(Object payload, MessageHeaders headers) { return MessageBuilder.createMessage(payload, headers); }
    }

    /** Records ERROR frames and transport failures of one session. */
    static final class Recorder extends StompSessionHandlerAdapter {
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile boolean transportClosed;

        @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(String.valueOf(headers.getFirst("message")));
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            transportClosed = true;
        }
    }

    private record Attempt(CompletableFuture<StompSession> future, Recorder recorder) {
        StompSession session() throws Exception {
            return future.get(15, TimeUnit.SECONDS);
        }
    }

    private Attempt connect(String origin, String bearer, String cookieToken) {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        if (cookieToken != null) {
            handshake.add("Cookie", "accessToken=" + cookieToken);
        }
        StompHeaders connect = new StompHeaders();
        if (bearer != null) {
            connect.add("Authorization", "Bearer " + bearer);
        }
        Recorder recorder = new Recorder();
        CompletableFuture<StompSession> f = client.connectAsync("http://127.0.0.1:" + port + "/ws", handshake, connect, recorder);
        f.thenAccept(open::add);
        return new Attempt(f, recorder);
    }

    private void assertConnectRefused(Attempt attempt, String reason) {
        assertThatThrownBy(attempt::session).as("CONNECT must not complete").isNotNull();
        if (reason != null) {
            assertThat(attempt.recorder().errors).as("ERROR frame reason").contains(reason);
        }
    }

    private BlockingQueue<String> subscribe(StompSession session) {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(QUEUE, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return received;
    }

    private void awaitSubscribed(TestTenant tenant, String email, int sessions) throws InterruptedException {
        String name = StompTenantAuthentication.userName(tenant.slug(), email);
        await().atMost(Duration.ofSeconds(10)).until(() -> userRegistry.findSubscriptions(
                s -> s.getSession().getUser().getName().equals(name) && QUEUE.equals(s.getDestination())).size() >= sessions);
        Thread.sleep(300); // the broker registers the subscription on the inbound executor just after the event
    }

    private String notifyNewPaidOrder(TestTenant tenant) {
        String orderNumber = "WS-" + System.nanoTime();
        Payment payment = Payment.builder().order(Order.builder().orderNumber(orderNumber).build()).build();
        tenants.runAs(tenant, () -> notificationService.notifyAdminsOfSuccessfulPayment(payment));
        return orderNumber;
    }

    // ------------------------------------------------------------------ CONNECT

    @Test
    void anonymousConnectIsRejected() {
        assertConnectRefused(connect(origin(acme), null, null), "Authentication required");
    }

    @Test
    void tokenOfTheOtherTenantIsRejected_evenForTheSameEmail() {
        // bravo's token for the e-mail that also exists in acme, presented on acme's origin (header and cookie)
        String bravoToken = token(bravo, SHARED_STAFF);
        assertConnectRefused(connect(origin(acme), bravoToken, null), "Token does not belong to this tenant");
        assertConnectRefused(connect(origin(acme), null, bravoToken), "Token does not belong to this tenant");
        assertThat(adminSessions.getConnectedAdmins("acme")).doesNotContain(SHARED_STAFF);
    }

    @Test
    void expiredTokenIsRejected() {
        assertConnectRefused(connect(origin(acme), expiredToken(acme, SHARED_STAFF), null), "Invalid or expired token");
    }

    @Test
    void handshakeWithoutTenantSignalOrForSuspendedTenantIsRejected() {
        String acmeToken = token(acme, SHARED_STAFF);
        // no Origin, no Referer (the dev header is a non-prod HTTP convenience only; a browser cannot send it here)
        assertConnectRefused(connect(null, acmeToken, null), null);
        assertConnectRefused(connect("http://nobody.shopfi.test", acmeToken, null), null);

        TestTenant parked = tenants.ensureTenant("wsparked");
        String parkedToken = token(parked, parked.adminEmail());
        jdbc.update("UPDATE master.tenants SET status = ? WHERE slug = 'wsparked'", TenantStatus.SUSPENDED.name());
        originPolicy.invalidateAll();
        try {
            assertConnectRefused(connect(origin(parked), parkedToken, null), null);
        } finally {
            jdbc.update("UPDATE master.tenants SET status = ? WHERE slug = 'wsparked'", TenantStatus.ACTIVE.name());
            originPolicy.invalidateAll();
        }
    }

    // ------------------------------------------------------------------ SUBSCRIBE / SEND

    @Test
    void customerSubscribingToTheAdminDestinationIsRejected() throws Exception {
        Attempt attempt = connect(origin(acme), token(acme, ACME_CUSTOMER), null);
        StompSession session = attempt.session();
        subscribe(session);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(attempt.recorder().errors).contains("Destination not allowed"));
        await().atMost(Duration.ofSeconds(10)).until(() -> !session.isConnected());
        assertThat(adminSessions.getConnectedAdmins("acme")).doesNotContain(ACME_CUSTOMER);
    }

    @Test
    void wildcardSubscriptionAndSendAreRejectedEvenForStaff() throws Exception {
        Attempt wildcard = connect(origin(acme), token(acme, SHARED_STAFF), null);
        wildcard.session().subscribe("/queue/**", new StompSessionHandlerAdapter() { });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(wildcard.recorder().errors).contains("Destination not allowed"));

        Attempt send = connect(origin(acme), token(acme, SHARED_STAFF), null);
        send.session().send("/app/anything", "x".getBytes(StandardCharsets.UTF_8));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(send.recorder().errors).contains("Destination not allowed"));
    }

    // ------------------------------------------------------------------ delivery

    @Test
    void adminOfTheRightTenantReceivesTheNewOrderNotification_crossTenantAdminReceivesNothing() throws Exception {
        StompSession acmeStaff = connect(origin(acme), token(acme, SHARED_STAFF), null).session();
        StompSession bravoStaff = connect(origin(bravo), token(bravo, SHARED_STAFF), null).session();
        BlockingQueue<String> acmeInbox = subscribe(acmeStaff);
        BlockingQueue<String> bravoInbox = subscribe(bravoStaff);
        awaitSubscribed(acme, SHARED_STAFF, 1);
        awaitSubscribed(bravo, SHARED_STAFF, 1);
        assertThat(adminSessions.getConnectedAdmins("acme")).contains(SHARED_STAFF);
        assertThat(adminSessions.getConnectedAdmins("bravo")).contains(SHARED_STAFF);

        String orderNumber = notifyNewPaidOrder(acme);

        String body = acmeInbox.poll(15, TimeUnit.SECONDS);
        assertThat(body).as("acme admin receives acme's paid-order notification").isNotNull()
                .contains(orderNumber).contains("PAYMENT_SUCCESS");
        assertThat(bravoInbox.poll(2, TimeUnit.SECONDS)).as("bravo admin with the same e-mail receives nothing").isNull();
        assertThat(acmeInbox.poll(500, TimeUnit.MILLISECONDS)).as("exactly one push").isNull();
        Integer delivered = jdbc.queryForObject("SELECT count(*) FROM tenant_acme.notification_recipients r "
                + "JOIN tenant_acme.notifications n ON n.id = r.notification_id JOIN tenant_acme.users u ON u.id = r.user_id "
                + "WHERE n.reference_id = ? AND u.email = ? AND r.delivered_at IS NOT NULL", Integer.class, orderNumber, SHARED_STAFF);
        assertThat(delivered).isEqualTo(1);
        Integer bravoRows = jdbc.queryForObject("SELECT count(*) FROM tenant_bravo.notifications WHERE reference_id = ?",
                Integer.class, orderNumber);
        assertThat(bravoRows).isZero();
    }

    @Test
    void cookieAuthenticatedAdminSocketStillWorksEndToEnd_andDisconnectUnregisters() throws Exception {
        // what a browser does: the accessToken cookie on the handshake, no Authorization header
        StompSession session = connect(origin(acme), null, token(acme, SHARED_STAFF)).session();
        BlockingQueue<String> inbox = subscribe(session);
        awaitSubscribed(acme, SHARED_STAFF, 1);

        String orderNumber = notifyNewPaidOrder(acme);
        assertThat(inbox.poll(15, TimeUnit.SECONDS)).isNotNull().contains(orderNumber);

        session.disconnect();
        await().atMost(Duration.ofSeconds(10)).until(() -> !adminSessions.isAdminConnected("acme", SHARED_STAFF));
    }
}
