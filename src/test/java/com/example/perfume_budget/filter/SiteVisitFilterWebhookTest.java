package com.example.perfume_budget.filter;

import com.example.perfume_budget.repository.SiteVisitRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** API-04 rework: the webhook URL carries the tenant's secret token, so it is never stored as a visited page. */
class SiteVisitFilterWebhookTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/webhook/SECRET-TOKEN-123", "/api/v1/webhook", "//api/v1//webhook/SECRET-TOKEN-123",
            "/API/V1/Webhook/SECRET-TOKEN-123", "/api/v1/webhook;x=1/SECRET-TOKEN-123",
            // API-04 rework 2 (probe P5): percent-encoded, double-encoded, mixed with // and ;params
            "/api/v1/%77ebhook/SECRET-TOKEN-123", "/api/v1/%57%45%42%48%4F%4F%4B/SECRET-TOKEN-123",
            "/api/v1/%2577ebhook/SECRET-TOKEN-123", "/api/v1/%25%37%37ebhook/SECRET-TOKEN-123",
            "/api/v1/%252577ebhook/SECRET-TOKEN-123", "//api//v1//%77ebhook//SECRET-TOKEN-123",
            "/api/v1/%77ebhook;jsessionid=1/SECRET-TOKEN-123", "/api;a=b/v1;c/web%68ook;d/SECRET-TOKEN-123",
            "/api%2Fv1%2Fwebhook%2FSECRET-TOKEN-123", "/%61pi/v1/webhook/SECRET-TOKEN-123", "/api/v1/webhook%3Bx/SECRET-TOKEN-123",
            // EVT-01 (API-04 review Q6): overlong / invalid UTF-8 and malformed escapes are unrecordable, whatever
            // they would decode to (Java's lenient decoder turns %C1%A2 into U+FFFD, so "we%C1%A2hook" looked unrelated)
            "/api/v1/we%C1%A2hook/SECRET-TOKEN-123", "/api/v1/%C1%B7ebhook/SECRET-TOKEN-123",
            "/api/v1/webhook%C0%AFSECRET-TOKEN-123", "/api/v1/we%25C1%25A2hook/SECRET-TOKEN-123",
            "/api/v1/we%E0%81%A2hook/SECRET-TOKEN-123", "/api/v1/we%FFhook/SECRET-TOKEN-123",
            "/api/v1/we%C3hook/SECRET-TOKEN-123", "/api/v1/we%ED%A0%80hook/SECRET-TOKEN-123",
            "/api/v1/%zz/webhook", "/api/v1/we%7hook/SECRET-TOKEN-123", "/api/v1/wehook/SECRET%"})
    void webhookPathsAreNeverRecorded(String uri) throws Exception {
        SiteVisitRepository repo = mock(SiteVisitRepository.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        MockFilterChain chain = new MockFilterChain();

        new SiteVisitFilter(repo).doFilter(req, new MockHttpServletResponse(), chain);

        verifyNoInteractions(repo);
        assertThat(chain.getRequest()).as("the request still goes through").isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/products", "/api/v1/webhooks-info", "/api/v1/webhookx/a", "/api/v1/%77ebhooks-info",
            "/api/v1/products%2Fwebhook", "/api/v1/caf%C3%A9", "/api/v1/products/%E2%82%AC"})
    void otherPathsAreStillRecorded(String uri) throws Exception {
        SiteVisitRepository repo = mock(SiteVisitRepository.class);
        new SiteVisitFilter(repo).doFilter(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse(),
                new MockFilterChain());
        verify(repo).save(any());
    }

    @org.junit.jupiter.api.Test
    void strictDecodingRejectsOverlongInvalidAndMalformedButKeepsValidUtf8() {
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding("/api/v1/we%C1%A2hook/x")).isTrue();
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding("/api/v1/we%25C1%25A2hook/x")).as("double-encoded").isTrue();
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding("/a%zz")).isTrue();
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding("/a%4")).isTrue();
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding("/caf%C3%A9")).isFalse();
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding("/caf\u00e9")).isFalse();
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding("/%2577ebhook")).isFalse();
        assertThat(SiteVisitFilter.hasInvalidPercentEncoding(null)).isFalse();
    }
}
