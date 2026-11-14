package com.example.perfume_budget.config.ws;

import lombok.RequiredArgsConstructor;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final AuthChannelInterceptor authChannelInterceptor;

    private final TenantOriginPolicy originPolicy;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry){
        registry.addEndpoint("/ws")
                // Spring's own origin check is static, so it is switched to "any" here and the real decision is made by
                // TenantOriginHandshakeInterceptor (same TenantOriginPolicy as REST CORS) and by the Spring Security
                // CorsFilter, which also fronts /ws/**. A disallowed Origin never reaches a session.
                .setAllowedOriginPatterns("*")
                .addInterceptors(new TenantOriginHandshakeInterceptor(originPolicy), new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            HttpServletRequest req = servletRequest.getServletRequest();
                            Cookie[] cookies = req.getCookies();
                            if (cookies != null) {
                                for (Cookie cookie : cookies) {
                                    if ("accessToken".equals(cookie.getName())) {
                                        attributes.put("accessToken", cookie.getValue());
                                    }
                                }
                            }
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
                    }
                })
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry){
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
