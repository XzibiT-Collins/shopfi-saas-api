package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.RequestTenantResolver;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** SEC-03c: the handshake binds the tenant with the HTTP rules and refuses unknown / suspended / missing tenants. */
class TenantHandshakeInterceptorTest {

    private TenantRegistryService registry;
    private TenantResolutionProperties props;
    private MockEnvironment env;
    private TenantHandshakeInterceptor interceptor;

    private static Tenant tenant(String slug, TenantStatus status) {
        return Tenant.builder().slug(slug).schemaName("tenant_" + slug).status(status).build();
    }

    @BeforeEach
    void setUp() {
        registry = mock(TenantRegistryService.class);
        when(registry.findByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(registry.findBySlug(anyString())).thenReturn(Optional.empty());
        when(registry.findBySlug("acme")).thenReturn(Optional.of(tenant("acme", TenantStatus.ACTIVE)));
        when(registry.findBySlug("sleepy")).thenReturn(Optional.of(tenant("sleepy", TenantStatus.SUSPENDED)));
        when(registry.findBySlug("fresh")).thenReturn(Optional.of(tenant("fresh", TenantStatus.PROVISIONING)));
        when(registry.findByCustomDomain("shop.acme.example")).thenReturn(Optional.of(tenant("acme", TenantStatus.ACTIVE)));
        props = new TenantResolutionProperties();
        props.setBaseDomain("shopfi.test");
        props.getHeaderResolution().setEnabled(true);
        props.setPlatformDomains(java.util.List.of("api.shopfi.test"));
        env = new MockEnvironment();
        interceptor = new TenantHandshakeInterceptor(new RequestTenantResolver(registry, props, env));
    }

    private record Result(boolean accepted, int status, Map<String, Object> attrs) { }

    private Result handshake(MockHttpServletRequest req) {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(res);
        Map<String, Object> attrs = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(new ServletServerHttpRequest(req), response, mock(WebSocketHandler.class), attrs);
        return new Result(ok, res.getStatus(), attrs);
    }

    private static MockHttpServletRequest origin(String origin) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/ws/123/abc/websocket");
        if (origin != null) r.addHeader("Origin", origin);
        return r;
    }

    @Test
    void subdomainAndCustomDomainOriginsBindTheTenant() {
        Result sub = handshake(origin("https://acme.shopfi.test"));
        assertThat(sub.accepted()).isTrue();
        assertThat(sub.attrs().get(TenantHandshakeInterceptor.TENANT_ATTR)).isEqualTo(TenantIdentity.tenant("acme", "tenant_acme"));
        Result custom = handshake(origin("https://shop.acme.example"));
        assertThat(custom.accepted()).isTrue();
        assertThat(custom.attrs().get(TenantHandshakeInterceptor.TENANT_ATTR)).isEqualTo(TenantIdentity.tenant("acme", "tenant_acme"));
    }

    @Test
    void refererFallbackAndDevHeaderFollowTheHttpRules() {
        MockHttpServletRequest referer = origin(null);
        referer.addHeader("Referer", "https://acme.shopfi.test/admin");
        assertThat(handshake(referer).accepted()).isTrue();

        MockHttpServletRequest header = origin(null);
        header.addHeader("X-Tenant-ID", "acme");
        assertThat(handshake(header).accepted()).isTrue();

        env.setActiveProfiles("prod");
        Result prod = handshake(header);
        assertThat(prod.accepted()).as("dev header ignored in prod").isFalse();
        assertThat(prod.status()).isEqualTo(400);
    }

    @Test
    void unknownSuspendedUnavailableAndMissingTenantsAreRefused() {
        Result unknown = handshake(origin("https://nobody.shopfi.test"));
        assertThat(unknown.accepted()).isFalse();
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.attrs()).doesNotContainKey(TenantHandshakeInterceptor.TENANT_ATTR);

        Result platform = handshake(origin("https://api.shopfi.test"));
        assertThat(platform.accepted()).isFalse();
        assertThat(platform.status()).isEqualTo(404);

        Result suspended = handshake(origin("https://sleepy.shopfi.test"));
        assertThat(suspended.accepted()).isFalse();
        assertThat(suspended.status()).isEqualTo(423);

        Result provisioning = handshake(origin("https://fresh.shopfi.test"));
        assertThat(provisioning.accepted()).isFalse();
        assertThat(provisioning.status()).isEqualTo(404);

        Result none = handshake(origin(null));
        assertThat(none.accepted()).isFalse();
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.attrs()).isEmpty();
    }

    @Test
    void accessTokenCookieIsCopiedOnlyForAnAcceptedHandshake() {
        MockHttpServletRequest ok = origin("https://acme.shopfi.test");
        ok.setCookies(new Cookie("other", "x"), new Cookie("accessToken", "tok"));
        assertThat(handshake(ok).attrs()).containsEntry(TenantHandshakeInterceptor.ACCESS_TOKEN_ATTR, "tok");

        MockHttpServletRequest refused = origin("https://nobody.shopfi.test");
        refused.setCookies(new Cookie("accessToken", "tok"));
        assertThat(handshake(refused).attrs()).isEmpty();
    }

    @Test
    void nonServletRequestIsRefused() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(res);
        boolean ok = interceptor.beforeHandshake(mock(ServerHttpRequest.class), response, mock(WebSocketHandler.class), new HashMap<>());
        assertThat(ok).isFalse();
        assertThat(res.getStatus()).isEqualTo(400);
    }
}
