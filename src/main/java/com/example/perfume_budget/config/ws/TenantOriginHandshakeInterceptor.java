package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.WebUtils;

import java.util.List;
import java.util.Map;

/**
 * Rejects a WebSocket/SockJS handshake whose {@code Origin} is not allowed by {@link TenantOriginPolicy}
 * (active tenant subdomain/custom domain or the static list) with 403. A request with no Origin (a non-browser
 * client) or the same origin as the API host passes; STOMP frames are still authenticated by AuthChannelInterceptor.
 */
public class TenantOriginHandshakeInterceptor implements HandshakeInterceptor {

    private final TenantOriginPolicy policy;

    public TenantOriginHandshakeInterceptor(TenantOriginPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> origins = request.getHeaders().get("Origin");
        if (origins == null || origins.isEmpty()) {
            return true;
        }
        if (origins.size() == 1 && (policy.isAllowed(origins.get(0), clientKey(request)) || WebUtils.isSameOrigin(request))) {
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    private static String clientKey(ServerHttpRequest request) {
        return request instanceof org.springframework.http.server.ServletServerHttpRequest s
                ? com.example.perfume_budget.security.ClientIps.rateLimitKey(s.getServletRequest()) : "-";
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
