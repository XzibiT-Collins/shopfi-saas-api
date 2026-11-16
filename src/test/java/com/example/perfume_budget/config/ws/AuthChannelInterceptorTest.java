package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.security.AuthUser;
import com.example.perfume_budget.security.CustomUserDetailsService;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** SEC-03c: unit coverage of every CONNECT / SUBSCRIBE / SEND decision of {@link AuthChannelInterceptor}. */
class AuthChannelInterceptorTest {

    private static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final TenantIdentity ACME = TenantIdentity.tenant("acme", "tenant_acme");

    private final JWTUtil jwt = new JWTUtil();
    private CustomUserDetailsService users;
    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    private AuthChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);
    private final AtomicReference<TenantIdentity> tenantDuringLookup = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwt, "jwtSecret", SECRET);
        users = mock(CustomUserDetailsService.class);
        when(users.loadUserByUsername(anyString())).thenAnswer(i -> {
            tenantDuringLookup.set(TenantContext.get());
            String email = i.getArgument(0);
            if (email.startsWith("ghost")) {
                throw new UsernameNotFoundException("no");
            }
            UserRole role = email.startsWith("customer") ? UserRole.CUSTOMER : UserRole.ADMIN;
            return new AuthUser(User.builder().id(1L).email(email).roles(role).isActive(!email.startsWith("disabled")).build());
        });
        interceptor = new AuthChannelInterceptor(jwt, users, now::get);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String token(String email, String tenant, long expiresAtMillis) {
        var b = Jwts.builder().subject(email).issuedAt(new Date(expiresAtMillis - 3_600_000)).expiration(new Date(expiresAtMillis))
                .claims(tenant == null ? Map.of() : Map.of("tenant", tenant));
        return b.signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact();
    }

    private String valid(String email) {
        return token(email, "acme", now.get() + 600_000);
    }

    private static Map<String, Object> attrs(TenantIdentity tenant, String cookieToken) {
        Map<String, Object> a = new HashMap<>();
        if (tenant != null) a.put(TenantHandshakeInterceptor.TENANT_ATTR, tenant);
        if (cookieToken != null) a.put(TenantHandshakeInterceptor.ACCESS_TOKEN_ATTR, cookieToken);
        return a;
    }

    private static StompHeaderAccessor frame(StompCommand cmd, Map<String, Object> attrs) {
        StompHeaderAccessor a = StompHeaderAccessor.create(cmd);
        a.setSessionId("s1");
        a.setSessionAttributes(attrs);
        a.setLeaveMutable(true);
        return a;
    }

    private static Message<byte[]> msg(StompHeaderAccessor a) {
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private StompHeaderAccessor connect(Map<String, Object> attrs, String bearer) {
        StompHeaderAccessor a = frame(StompCommand.CONNECT, attrs);
        if (bearer != null) a.setNativeHeader("Authorization", "Bearer " + bearer);
        interceptor.preSend(msg(a), channel);
        return a;
    }

    private void refused(Runnable r, String reason) {
        assertThatThrownBy(r::run).isInstanceOf(StompAccessDeniedException.class).hasMessage(reason);
        assertThat(TenantContext.get()).as("no tenant leaks out of a refused frame").isNull();
    }

    @Test
    void connectWithHeaderTokenAuthenticatesInTheHandshakeTenantAndRestoresContext() {
        Map<String, Object> attrs = attrs(ACME, null);
        StompHeaderAccessor a = connect(attrs, valid("admin@acme.test"));
        assertThat(a.getUser()).isInstanceOf(StompTenantAuthentication.class);
        StompTenantAuthentication user = (StompTenantAuthentication) a.getUser();
        assertThat(user.getName()).isEqualTo("acme:admin@acme.test");
        assertThat(user.getTenantSlug()).isEqualTo("acme");
        assertThat(user.getEmail()).isEqualTo("admin@acme.test");
        assertThat(tenantDuringLookup.get()).as("user lookup ran in the handshake tenant").isEqualTo(ACME);
        assertThat(TenantContext.get()).as("context cleared after CONNECT").isNull();
        assertThat(attrs.get(AuthChannelInterceptor.TOKEN_EXPIRY_ATTR)).isInstanceOf(Long.class);
    }

    @Test
    void connectRestoresAPreviouslyBoundTenant() {
        TenantIdentity other = TenantIdentity.tenant("zeta", "tenant_zeta");
        TenantContext.set(other);
        connect(attrs(ACME, null), valid("admin@acme.test"));
        assertThat(tenantDuringLookup.get()).isEqualTo(ACME);
        assertThat(TenantContext.get()).isEqualTo(other);
    }

    @Test
    void connectWithCookieTokenAuthenticates() {
        StompHeaderAccessor a = connect(attrs(ACME, valid("admin@acme.test")), null);
        assertThat(a.getUser().getName()).isEqualTo("acme:admin@acme.test");
    }

    @Test
    void stompCommandIsTreatedLikeConnect() {
        StompHeaderAccessor a = frame(StompCommand.STOMP, attrs(ACME, null));
        assertThatThrownBy(() -> interceptor.preSend(msg(a), channel)).hasMessage("Authentication required");
    }

    @Test
    void anonymousConnectIsRefused() {
        refused(() -> connect(attrs(ACME, null), null), "Authentication required");
        refused(() -> connect(attrs(ACME, "  "), null), "Authentication required");
        StompHeaderAccessor a = frame(StompCommand.CONNECT, attrs(ACME, null));
        a.setNativeHeader("Authorization", "Basic abc");
        refused(() -> interceptor.preSend(msg(a), channel), "Authentication required");
        verifyNoInteractions(users);
    }

    @Test
    void connectWithoutHandshakeTenantIsRefused() {
        refused(() -> connect(attrs(null, null), valid("admin@acme.test")), "Tenant could not be determined");
        refused(() -> connect(attrs(TenantIdentity.platform(), null), valid("admin@acme.test")), "Tenant could not be determined");
        refused(() -> connect(attrs(TenantIdentity.template(), null), valid("admin@acme.test")), "Tenant could not be determined");
        verifyNoInteractions(users);
    }

    @Test
    void otherTenantOrMissingOrPlatformClaimIsRefusedBeforeAnyUserLookup() {
        long exp = now.get() + 600_000;
        refused(() -> connect(attrs(ACME, null), token("admin@acme.test", "bravo", exp)), "Token does not belong to this tenant");
        refused(() -> connect(attrs(ACME, null), token("admin@acme.test", null, exp)), "Token does not belong to this tenant");
        refused(() -> connect(attrs(ACME, null), token("admin@acme.test", "__platform__", exp)), "Token does not belong to this tenant");
        verifyNoInteractions(users);
    }

    @Test
    void expiredMalformedOrForeignSignedTokensAreRefused() {
        refused(() -> connect(attrs(ACME, null), token("admin@acme.test", "acme", now.get() - 1000)), "Invalid or expired token");
        refused(() -> connect(attrs(ACME, null), "not-a-jwt"), "Invalid or expired token");
        String foreign = Jwts.builder().subject("admin@acme.test").claims(Map.of("tenant", "acme"))
                .expiration(new Date(now.get() + 600_000))
                .signWith(Keys.hmacShaKeyFor(new byte[32])).compact();
        refused(() -> connect(attrs(ACME, null), foreign), "Invalid or expired token");
    }

    @Test
    void unknownOrDisabledUserIsRefused() {
        refused(() -> connect(attrs(ACME, null), valid("ghost@acme.test")), "Invalid or expired token");
        refused(() -> connect(attrs(ACME, null), valid("disabled@acme.test")), "Invalid or expired token");
    }

    private Map<String, Object> connected(String email) {
        Map<String, Object> attrs = attrs(ACME, null);
        StompHeaderAccessor a = connect(attrs, valid(email));
        attrs.put("user", a.getUser());
        return attrs;
    }

    private void subscribe(Map<String, Object> attrs, String destination) {
        StompHeaderAccessor a = frame(StompCommand.SUBSCRIBE, attrs);
        a.setUser((java.security.Principal) attrs.get("user"));
        a.setDestination(destination);
        a.setSubscriptionId("sub-0");
        interceptor.preSend(msg(a), channel);
    }

    private void send(Map<String, Object> attrs, String destination) {
        StompHeaderAccessor a = frame(StompCommand.SEND, attrs);
        a.setUser((java.security.Principal) attrs.get("user"));
        a.setDestination(destination);
        interceptor.preSend(msg(a), channel);
    }

    @Test
    void staffMaySubscribeToTheirNotificationQueueOnly() {
        Map<String, Object> admin = connected("admin@acme.test");
        subscribe(admin, "/user/queue/notifications");
        for (String d : new String[]{"/queue/**", "/queue/notifications", "/queue/notifications-users1", "/topic/admin",
                "/user/queue/notifications/../x", "/user/acme:other@acme.test/queue/notifications", "/topic/**", null}) {
            refused(() -> subscribe(admin, d), "Destination not allowed");
        }
    }

    @Test
    void customerMayNotSubscribeToStaffDestinations() {
        Map<String, Object> customer = connected("customer@acme.test");
        refused(() -> subscribe(customer, "/user/queue/notifications"), "Destination not allowed");
        refused(() -> subscribe(customer, "/topic/admin"), "Destination not allowed");
    }

    @Test
    void sendIsAlwaysRefused() {
        Map<String, Object> admin = connected("admin@acme.test");
        refused(() -> send(admin, "/app/anything"), "Destination not allowed");
        refused(() -> send(admin, "/user/queue/notifications"), "Destination not allowed");
    }

    @Test
    void subscribeWithoutAuthenticatedPrincipalIsRefused() {
        Map<String, Object> attrs = attrs(ACME, null);
        refused(() -> subscribe(attrs, "/user/queue/notifications"), "Authentication required");
        attrs.put("user", new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin@acme.test", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        refused(() -> subscribe(attrs, "/user/queue/notifications"), "Authentication required");
        refused(() -> send(attrs, "/app/x"), "Authentication required");
    }

    @Test
    void principalOfAnotherTenantIsRefused() {
        Map<String, Object> admin = connected("admin@acme.test");
        admin.put(TenantHandshakeInterceptor.TENANT_ATTR, TenantIdentity.tenant("bravo", "tenant_bravo"));
        refused(() -> subscribe(admin, "/user/queue/notifications"), "Authentication required");
    }

    @Test
    void subscribeAfterTheTokenExpiredIsRefused() {
        Map<String, Object> admin = connected("admin@acme.test");
        now.addAndGet(600_000); // exactly the expiry instant
        refused(() -> subscribe(admin, "/user/queue/notifications"), "Session expired");
        admin.remove(AuthChannelInterceptor.TOKEN_EXPIRY_ATTR);
        now.addAndGet(-600_000);
        refused(() -> subscribe(admin, "/user/queue/notifications"), "Session expired");
    }

    @Test
    void otherCommandsPassUntouched() {
        for (StompCommand c : new StompCommand[]{StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT, StompCommand.ACK}) {
            Message<byte[]> m = msg(frame(c, attrs(null, null)));
            assertThat(interceptor.preSend(m, channel)).isSameAs(m);
        }
        Message<byte[]> noStomp = MessageBuilder.withPayload(new byte[0]).build();
        assertThat(interceptor.preSend(noStomp, channel)).isSameAs(noStomp);
    }

    @Test
    void handlingRunsWithTheSessionTenantAndClearsAfterwards() {
        MessageHandler handler = mock(MessageHandler.class);
        Message<byte[]> m = msg(frame(StompCommand.SUBSCRIBE, attrs(ACME, null)));
        interceptor.beforeHandle(m, channel, handler);
        assertThat(TenantContext.get()).isEqualTo(ACME);
        interceptor.afterMessageHandled(m, channel, handler, null);
        assertThat(TenantContext.get()).isNull();

        TenantContext.set(ACME); // a stale value on a pooled thread never survives into a tenant-less message
        interceptor.beforeHandle(msg(frame(StompCommand.SUBSCRIBE, attrs(null, null))), channel, handler);
        assertThat(TenantContext.get()).isNull();
    }
}
