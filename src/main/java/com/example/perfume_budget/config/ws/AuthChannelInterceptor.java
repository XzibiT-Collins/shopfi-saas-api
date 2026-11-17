package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.security.CustomUserDetailsService;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.tenancy.TenantScope;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Authenticates and authorises inbound STOMP frames (SEC-03c / WS-01).
 *
 * <ul>
 *   <li><b>CONNECT</b>: the session must carry the tenant bound by {@link TenantHandshakeInterceptor}. The token
 *       ({@code Authorization: Bearer} native header, else the {@code accessToken} cookie copied at handshake) must
 *       verify, be unexpired, carry a {@code tenant} claim equal to the handshake tenant, and name an enabled user of
 *       THAT tenant: the user lookup runs with the handshake tenant bound to {@link TenantContext} (restored
 *       afterwards). Anything else, including no token at all, is refused with an ERROR frame and the session closed.
 *       The session principal is a {@link StompTenantAuthentication} (tenant-qualified name). A second CONNECT on a
 *       session that already has that principal is refused ({@code Already connected}) and the session closed.</li>
 *   <li><b>SUBSCRIBE / SEND</b>: require that principal, of the session's tenant, with the CONNECT token not yet expired,
 *       and a destination allowed by {@link StompDestinationPolicy}.</li>
 *   <li>Message handling on the inbound executor runs with the session's tenant bound and cleared afterwards
 *       ({@link #beforeHandle} / {@link #afterMessageHandled}).</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthChannelInterceptor implements ExecutorChannelInterceptor {

    /** Session attribute: expiry (epoch millis) of the token the session connected with. */
    static final String TOKEN_EXPIRY_ATTR = "shopfi.tokenExpiresAt";

    private final JWTUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
    private final LongSupplier clock;

    @Autowired
    public AuthChannelInterceptor(JWTUtil jwtUtil, CustomUserDetailsService userDetailsService) {
        this(jwtUtil, userDetailsService, System::currentTimeMillis);
    }

    AuthChannelInterceptor(JWTUtil jwtUtil, CustomUserDetailsService userDetailsService, LongSupplier clock) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> connect(accessor);
            case SUBSCRIBE -> authorize(accessor, true);
            case SEND -> authorize(accessor, false);
            default -> {
                // UNSUBSCRIBE, ACK, NACK, BEGIN, COMMIT, ABORT, DISCONNECT: nothing to deliver to anyone.
            }
        }
        return message;
    }

    private void connect(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompTenantAuthentication) {
            // A second CONNECT on an authenticated session (SEC-03c review F3): never re-authenticate or swap the
            // principal in place; refuse it, which closes the session. Over a WebSocket, Spring's StompSubProtocolHandler
            // already refuses it before this point ("Session already exists"); this is the explicit second line.
            throw new StompAccessDeniedException("Already connected");
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        TenantIdentity tenant = sessionTenant(attributes);
        String token = token(accessor, attributes);
        if (token == null) {
            throw new StompAccessDeniedException("Authentication required");
        }

        TenantIdentity previous = TenantContext.get();
        TenantContext.set(tenant);
        try {
            Claims claims;
            try {
                claims = jwtUtil.parseToken(token);
            } catch (JwtException | IllegalArgumentException e) {
                throw new StompAccessDeniedException("Invalid or expired token");
            }
            Object claimTenant = claims.get("tenant");
            if (claimTenant == null || !tenant.slug().equals(claimTenant.toString())) {
                log.debug("STOMP CONNECT refused: token tenant does not match the handshake tenant");
                throw new StompAccessDeniedException("Token does not belong to this tenant");
            }
            String username = claims.getSubject();
            if (username == null || claims.getExpiration() == null) {
                throw new StompAccessDeniedException("Invalid or expired token");
            }
            UserDetails user;
            try {
                user = userDetailsService.loadUserByUsername(username);
            } catch (AuthenticationException e) {
                throw new StompAccessDeniedException("Invalid or expired token");
            }
            if (!jwtUtil.validateToken(token, user)) {
                throw new StompAccessDeniedException("Invalid or expired token");
            }
            accessor.setUser(new StompTenantAuthentication(tenant.slug(), user.getUsername(), user, user.getAuthorities()));
            attributes.put(TOKEN_EXPIRY_ATTR, claims.getExpiration().getTime());
            log.info("STOMP session authenticated for a user of tenant {}", tenant.slug());
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void authorize(StompHeaderAccessor accessor, boolean subscribe) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        TenantIdentity tenant = sessionTenant(attributes);
        Principal principal = accessor.getUser();
        if (!(principal instanceof StompTenantAuthentication user) || !tenant.slug().equals(user.getTenantSlug())) {
            throw new StompAccessDeniedException("Authentication required");
        }
        Object expiresAt = attributes.get(TOKEN_EXPIRY_ATTR);
        if (!(expiresAt instanceof Long expiry) || expiry <= clock.getAsLong()) {
            throw new StompAccessDeniedException("Session expired");
        }
        String destination = accessor.getDestination();
        boolean allowed = subscribe
                ? StompDestinationPolicy.maySubscribe(user, destination)
                : StompDestinationPolicy.maySend(user, destination);
        if (!allowed) {
            throw new StompAccessDeniedException("Destination not allowed");
        }
    }

    /** The tenant bound at handshake; fail closed when absent (never fall back to a neutral or default schema). */
    private static TenantIdentity sessionTenant(Map<String, Object> attributes) {
        Object value = attributes == null ? null : attributes.get(TenantHandshakeInterceptor.TENANT_ATTR);
        if (value instanceof TenantIdentity identity && identity.scope() == TenantScope.TENANT && identity.slug() != null) {
            return identity;
        }
        throw new StompAccessDeniedException("Tenant could not be determined");
    }

    private static String token(StompHeaderAccessor accessor, Map<String, Object> attributes) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && !header.substring(7).isBlank()) {
            return header.substring(7).strip();
        }
        Object cookie = attributes.get(TenantHandshakeInterceptor.ACCESS_TOKEN_ATTR);
        return cookie instanceof String s && !s.isBlank() ? s : null;
    }

    @Override
    public Message<?> beforeHandle(@NonNull Message<?> message, @NonNull MessageChannel channel,
                                   @NonNull MessageHandler handler) {
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
        Object value = attributes == null ? null : attributes.get(TenantHandshakeInterceptor.TENANT_ATTR);
        if (value instanceof TenantIdentity identity) {
            TenantContext.set(identity);
        } else {
            TenantContext.clear();
        }
        return message;
    }

    @Override
    public void afterMessageHandled(@NonNull Message<?> message, @NonNull MessageChannel channel,
                                    @NonNull MessageHandler handler, Exception ex) {
        TenantContext.clear();
    }
}
