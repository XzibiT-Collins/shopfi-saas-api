package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.tenancy.RequestTenantResolver;
import com.example.perfume_budget.tenancy.TenantIdentity;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Binds the WebSocket/SockJS session to a tenant at handshake time (SEC-03c), with exactly the HTTP rules
 * ({@link RequestTenantResolver}: Origin, then Referer, then the non-prod dev header). Runs after
 * {@link TenantOriginHandshakeInterceptor}. The resolved {@link TenantIdentity} is stored in the handshake attributes
 * (they become the STOMP session attributes) under {@link #TENANT_ATTR}; {@link AuthChannelInterceptor} reads it.
 *
 * <p>Refused, like the HTTP filter: unknown origin 404, suspended tenant 423, tenant not active 404, and (unlike HTTP,
 * where neutral paths exist) no tenant signal at all 400: a STOMP session is always tenant-scoped.
 * Also copies the {@code accessToken} cookie into {@link #ACCESS_TOKEN_ATTR} for CONNECT frames without a header.
 */
@Slf4j
public class TenantHandshakeInterceptor implements HandshakeInterceptor {

    public static final String TENANT_ATTR = "shopfi.tenant";
    public static final String ACCESS_TOKEN_ATTR = "accessToken";

    private final RequestTenantResolver resolver;

    public TenantHandshakeInterceptor(RequestTenantResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        HttpServletRequest req = servletRequest.getServletRequest();
        RequestTenantResolver.Resolution resolution = resolver.resolve(req);
        HttpStatusCode refusal = switch (resolution.outcome()) {
            case RESOLVED -> null;
            case NO_SIGNAL -> HttpStatus.BAD_REQUEST;
            case UNKNOWN, UNAVAILABLE -> HttpStatus.NOT_FOUND;
            case SUSPENDED -> HttpStatusCode.valueOf(423);
        };
        if (refusal != null) {
            log.debug("Refusing WebSocket handshake: tenant {}", resolution.outcome());
            response.setStatusCode(refusal);
            return false;
        }
        attributes.put(TENANT_ATTR, resolution.identity());
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ACCESS_TOKEN_ATTR.equals(cookie.getName())) {
                    attributes.put(ACCESS_TOKEN_ATTR, cookie.getValue());
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
