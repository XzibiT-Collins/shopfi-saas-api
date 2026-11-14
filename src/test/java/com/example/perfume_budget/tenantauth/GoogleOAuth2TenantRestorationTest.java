package com.example.perfume_budget.tenantauth;

import com.example.perfume_budget.security.oauth2.CustomOAuth2User;
import com.example.perfume_budget.security.oauth2.CustomOAuth2UserService;
import com.example.perfume_budget.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationSuccessHandler;
import com.example.perfume_budget.tenancy.TenantContext;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * TENANT-05 AC2: Google OAuth2 tenant restoration, with the Google user-info endpoint mocked by a local
 * HTTP server (no network, no real Google credentials). The authorize step goes through the real security
 * filter chain (MockMvc); the callback steps drive the same beans Spring Security calls on
 * /oauth2/callback/google: the authorization-request repository (restores TenantContext), the user service
 * and the success handler.
 */
class GoogleOAuth2TenantRestorationTest extends AbstractTenantWebTest {

    private static HttpServer userInfoServer;
    private static final AtomicReference<String> USERINFO_JSON = new AtomicReference<>();

    @Autowired HttpCookieOAuth2AuthorizationRequestRepository authRequestRepo;
    @Autowired CustomOAuth2UserService userService;
    @Autowired OAuth2AuthenticationSuccessHandler successHandler;
    @Autowired ClientRegistrationRepository clientRegistrations;

    @BeforeAll
    static void startMockGoogle() throws Exception {
        userInfoServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        userInfoServer.createContext("/userinfo", ex -> {
            byte[] b = USERINFO_JSON.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        userInfoServer.start();
    }

    @AfterAll
    static void stopMockGoogle() {
        userInfoServer.stop(0);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---- authorize (real filter chain) ----

    private MockHttpServletRequest authorize(String redirectUri, String tenantHeader) throws Exception {
        var req = get("/oauth2/authorize/google").param("redirect_uri", redirectUri);
        if (tenantHeader != null) {
            req.header("X-Tenant-ID", tenantHeader);
        }
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        String cookie = setCookieValue(r, "OAUTH2_AUTH_REQUEST");
        assertThat(cookie).as("authorization request persisted in cookie").isNotBlank();
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/oauth2/callback/google");
        callback.setCookies(new Cookie("OAUTH2_AUTH_REQUEST", cookie));
        return callback;
    }

    private OAuth2UserRequest googleUserRequest(String email, String name) {
        USERINFO_JSON.set("{\"sub\":\"g-" + UUID.randomUUID() + "\",\"email\":\"" + email + "\",\"name\":\"" + name
                + "\",\"picture\":\"https://img.example/x.png\"}");
        ClientRegistration google = clientRegistrations.findByRegistrationId("google");
        ClientRegistration mocked = ClientRegistration.withClientRegistration(google)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .userInfoUri("http://127.0.0.1:" + userInfoServer.getAddress().getPort() + "/userinfo")
                .userNameAttributeName("sub")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "fake-access-token",
                Instant.now(), Instant.now().plusSeconds(60), Set.of("profile", "email"));
        return new OAuth2UserRequest(mocked, token);
    }

    private int totalUsersNamed(String email) {
        return countUsers("tenant_acme", email) + countUsers("tenant_bravo", email) + countUsers("tenant_template", email);
    }

    // ---- AC2 ----

    @Test
    void ac2_tenantARedirectUriRestoresTenantA_userCreatedInAcmeOnly_jwtBoundToAcme() throws Exception {
        String redirect = "https://" + ACME_DOMAIN + "/oauth2/redirect";
        MockHttpServletRequest callback = authorize(redirect, null);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        OAuth2AuthorizationRequest restored = authRequestRepo.removeAuthorizationRequest(callback, resp);
        assertThat(restored).isNotNull();
        assertThat(TenantContext.getSlug()).as("tenant restored on callback").isEqualTo("acme");
        assertThat(callback.getAttribute(HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE))
                .isEqualTo(redirect);

        String email = "g-acme-" + UUID.randomUUID() + "@example.com";
        CustomOAuth2User user = (CustomOAuth2User) userService.loadUser(googleUserRequest(email, "Gee Acme"));
        assertThat(countUsers("tenant_acme", email)).isEqualTo(1);
        assertThat(countUsers("tenant_bravo", email)).isZero();
        assertThat(countUsers("tenant_template", email)).isZero();

        MockHttpServletResponse ok = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(callback, ok, new TestingAuthenticationToken(user, null));
        assertThat(ok.getRedirectedUrl()).startsWith(redirect).contains("success=true");
        String access = cookieFrom(ok, "accessToken");
        assertThat(jwtUtil.extractTenant(access)).isEqualTo("acme");
    }

    @Test
    void ac2_tenantBRedirectUriRestoresTenantB_sameGoogleEmailIsIndependentPerTenant() throws Exception {
        String email = "g-both-" + UUID.randomUUID() + "@example.com";
        for (String[] t : new String[][]{{"acme", ACME_DOMAIN}, {"bravo", BRAVO_DOMAIN}}) {
            MockHttpServletRequest callback = authorize("https://" + t[1] + "/oauth2/redirect", null);
            authRequestRepo.removeAuthorizationRequest(callback, new MockHttpServletResponse());
            assertThat(TenantContext.getSlug()).isEqualTo(t[0]);
            CustomOAuth2User user = (CustomOAuth2User) userService.loadUser(googleUserRequest(email, "Gee " + t[0]));
            MockHttpServletResponse ok = new MockHttpServletResponse();
            successHandler.onAuthenticationSuccess(callback, ok, new TestingAuthenticationToken(user, null));
            assertThat(jwtUtil.extractTenant(cookieFrom(ok, "accessToken"))).isEqualTo(t[0]);
            TenantContext.clear();
        }
        assertThat(countUsers("tenant_acme", email)).isEqualTo(1);
        assertThat(countUsers("tenant_bravo", email)).isEqualTo(1);
        assertThat(countUsers("tenant_template", email)).isZero();
    }

    @Test
    void ac2_unregisteredRedirectUriIsRejected_noTenantRestored_noUserCreated_noDefaultSchemaFallback() throws Exception {
        String email = "g-evil-" + UUID.randomUUID() + "@example.com";
        for (String evil : List.of("https://evil.example/steal", "https://acme-shop.test.evil.example/x",
                "https://shop.test/x", "//evil.example/x")) {
            MockHttpServletRequest callback = authorize(evil, null);
            OAuth2AuthorizationRequest restored = authRequestRepo.removeAuthorizationRequest(callback, new MockHttpServletResponse());
            assertThat(restored).isNotNull();
            assertThat(TenantContext.get()).as("no tenant for " + evil).isNull();
            assertThat(callback.getAttribute(HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE))
                    .as("untrusted redirect must not be stashed for " + evil).isNull();

            assertThatThrownBy(() -> userService.loadUser(googleUserRequest(email, "Mallory")))
                    .isInstanceOf(OAuth2AuthenticationException.class);
        }
        assertThat(totalUsersNamed(email)).as("no user created in any schema").isZero();
    }

    @Test
    void ac2_unregisteredHostIsRejectedEvenWithAValidTenantHeader() throws Exception {
        // X-Tenant-ID only legitimises allow-listed dev hosts (localhost/127.0.0.1), never an arbitrary host.
        MockHttpServletRequest callback = authorize("https://evil.example/steal", "acme");
        authRequestRepo.removeAuthorizationRequest(callback, new MockHttpServletResponse());
        assertThat(TenantContext.get()).isNull();
        String email = "g-hdr-" + UUID.randomUUID() + "@example.com";
        assertThatThrownBy(() -> userService.loadUser(googleUserRequest(email, "Mallory")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(totalUsersNamed(email)).isZero();
    }

    @Test
    void ac2_devLocalhostRedirectRestoresTenantOnlyWithValidHeader() throws Exception {
        MockHttpServletRequest noHeader = authorize("http://localhost:3000/oauth2/redirect", null);
        authRequestRepo.removeAuthorizationRequest(noHeader, new MockHttpServletResponse());
        assertThat(TenantContext.get()).as("localhost without X-Tenant-ID").isNull();

        MockHttpServletRequest unknown = authorize("http://localhost:3000/oauth2/redirect", "nosuchtenant");
        authRequestRepo.removeAuthorizationRequest(unknown, new MockHttpServletResponse());
        assertThat(TenantContext.get()).as("localhost with unknown tenant").isNull();

        MockHttpServletRequest bravoDev = authorize("http://localhost:3000/oauth2/redirect", "bravo");
        authRequestRepo.removeAuthorizationRequest(bravoDev, new MockHttpServletResponse());
        assertThat(TenantContext.getSlug()).isEqualTo("bravo");
    }

    @Test
    void ac2_userServiceNeverFallsBackToDefaultSchemaWhenNoTenantContext() {
        TenantContext.clear();
        String email = "g-none-" + UUID.randomUUID() + "@example.com";
        assertThatThrownBy(() -> userService.loadUser(googleUserRequest(email, "Nobody")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(totalUsersNamed(email)).isZero();
        Integer anywhere = jdbc.queryForObject("""
                SELECT coalesce(sum((xpath('/row/c/text()', query_to_xml(
                  format('SELECT count(*) AS c FROM %I.users WHERE email = %L', table_schema, ?), false, true, '')))[1]::text::int), 0)
                FROM information_schema.tables WHERE table_name = 'users'""".replace("?", "'" + email + "'"), Integer.class);
        assertThat(anywhere).as("no users table in any schema got the row").isZero();
    }

    @Test
    void ac2_successHandlerNeverFollowsAnUntrustedOrCrossTenantRedirect() throws Exception {
        MockHttpServletRequest callback = authorize("https://" + ACME_DOMAIN + "/oauth2/redirect", null);
        authRequestRepo.removeAuthorizationRequest(callback, new MockHttpServletResponse());
        String email = "g-redir-" + UUID.randomUUID() + "@example.com";
        CustomOAuth2User user = (CustomOAuth2User) userService.loadUser(googleUserRequest(email, "Gee"));

        for (String bad : List.of("https://evil.example/steal", "https://" + BRAVO_DOMAIN + "/oauth2/redirect")) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/callback/google");
            req.setAttribute(HttpCookieOAuth2AuthorizationRequestRepository.REQUEST_REDIRECT_ATTRIBUTE, bad);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            successHandler.onAuthenticationSuccess(req, resp, new TestingAuthenticationToken(user, null));
            assertThat(resp.getRedirectedUrl()).as("redirect for " + bad)
                    .doesNotContain("evil.example").doesNotContain(BRAVO_DOMAIN)
                    .startsWith("http://localhost:3000/oauth2/redirect"); // configured fallback
        }
    }

    private static String cookieFrom(MockHttpServletResponse resp, String name) {
        for (String h : resp.getHeaders("Set-Cookie")) {
            if (h.startsWith(name + "=")) {
                String v = h.substring(name.length() + 1);
                return v.substring(0, v.indexOf(';'));
            }
        }
        return null;
    }
}
