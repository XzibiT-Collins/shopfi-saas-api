package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import com.example.perfume_budget.tenancy.RequestTenantResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final AuthChannelInterceptor authChannelInterceptor;

    private final TenantOriginPolicy originPolicy;

    private final RequestTenantResolver tenantResolver;

    private final StompSessionRegistry sessionRegistry;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry){
        registry.addEndpoint("/ws")
                // Spring's own origin check is static, so it is switched to "any" here and the real decision is made by
                // TenantOriginHandshakeInterceptor (same TenantOriginPolicy as REST CORS) and by the Spring Security
                // CorsFilter, which also fronts /ws/**. A disallowed Origin never reaches a session.
                // SEC-03c: TenantHandshakeInterceptor then binds the session to the tenant resolved with the HTTP rules.
                .setAllowedOriginPatterns("*")
                .addInterceptors(new TenantOriginHandshakeInterceptor(originPolicy),
                        new TenantHandshakeInterceptor(tenantResolver))
                .withSockJS();
        registry.setErrorHandler(new StompErrorHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry){
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /** SEC-03e F5: keep a handle on every socket so the server can close it (expiry, disabled user, suspended tenant). */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionRegistry.socketOpened(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessionRegistry.socketClosed(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        });
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
