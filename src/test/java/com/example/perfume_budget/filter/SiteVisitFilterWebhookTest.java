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
            "/api/v1/%zz/webhook", "/api/v1/we%7hook/SECRET-TOKEN-123", "/api/v1/wehook/SECRET%",
            // EVT-01 rework 1 (F2): valid UTF-8 that only LOOKS like the webhook: zero-width / invisible characters,
            // full-width and mathematical letters, accents, Cyrillic / Greek / small-capital look-alikes, separators
            "/api/v1/w%E2%80%8Bebhook/SECRET-TOKEN-123", "/api/v1/%EF%BD%97ebhook/SECRET-TOKEN-123",
            "/api/v1/web%C2%ADhook/SECRET-TOKEN-123", "/api/v1/we%E2%81%A0bhook/SECRET-TOKEN-123",
            "/api/v1/%EF%BC%B7%EF%BC%A5%EF%BC%A2hook/SECRET-TOKEN-123", "/api/v1/w%C3%A9bhook/SECRET-TOKEN-123",
            "/api/v1/we%CC%81bhook/SECRET-TOKEN-123", "/api/v1/w%D0%B5bh%D0%BEok/SECRET-TOKEN-123",
            "/api/v1/webho%CE%BFk/SECRET-TOKEN-123", "/api/v1/%F0%9D%90%B0ebhook/SECRET-TOKEN-123",
            "/api/v1/%E1%B4%A1ebhook/SECRET-TOKEN-123", "/api/v1/web-hook/SECRET-TOKEN-123", "/api/v1/web_hook/x",
            "/api/v1/web.hook/x", "/api/v1/w%25E2%2580%258Bebhook/SECRET-TOKEN-123",
            // previously recorded (kept as "other paths"), now unrecordable: they contain the word and are not routed
            "/api/v1/webhooks-info", "/api/v1/webhookx/a", "/api/v1/%77ebhooks-info", "/api/v1/products%2Fwebhook"})
    void webhookPathsAreNeverRecorded(String uri) throws Exception {
        SiteVisitRepository repo = mock(SiteVisitRepository.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        MockFilterChain chain = new MockFilterChain();

        new SiteVisitFilter(repo).doFilter(req, new MockHttpServletResponse(), chain);

        verifyNoInteractions(repo);
        assertThat(chain.getRequest()).as("the request still goes through").isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/products", "/api/v1/hooks", "/api/v1/web/shop", "/api/v1/caf%C3%A9",
            "/api/v1/products/%E2%82%AC", "/api/v1/product/a%20b", "/api/v1/product/a+b", "/api/v1/product/%F0%9F%98%80",
            "/api/v1/product/caf%C3%A9-noir", "/api/v1/product/chanel-no-5-eau-de-parfum-100ml-limited-edition-2026"})
    void otherPathsAreStillRecordedUnchanged(String uri) throws Exception {
        SiteVisitRepository repo = mock(SiteVisitRepository.class);
        new SiteVisitFilter(repo).doFilter(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse(),
                new MockFilterChain());
        org.mockito.ArgumentCaptor<com.example.perfume_budget.model.SiteVisit> saved =
                org.mockito.ArgumentCaptor.forClass(com.example.perfume_budget.model.SiteVisit.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getPage()).isEqualTo(uri);
    }

    /** F2 second layer: a token-shaped segment is never stored, whatever the route is called. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/hook/", "/api/v1/whatever/", "/api/v1/whatever/x%2F", "/api/v1/x;y=", "/"})
    void tokenShapedSegmentsAreRedacted(String prefix) throws Exception {
        String token = "kq3J7xYc0_Fv-9LmB2nRzT8wHa5dE1sQpU4oViGy6Xt";
        // every 8th character percent-encoded: no raw run of 32 token characters is left, only the decoded form has it
        StringBuilder sparse = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            sparse.append(i % 8 == 7 ? String.format("%%%02X", (int) ch) : String.valueOf(ch));
        }
        assertThat(sparse.toString()).doesNotContainPattern("[A-Za-z0-9_-]{32}");
        for (String spelled : new String[]{token, token.replace("k", "%6B"), token.replace("k", "%256B"), sparse.toString(),
                sparse.toString().replace("%", "%25")}) {
            SiteVisitRepository repo = mock(SiteVisitRepository.class);
            new SiteVisitFilter(repo).doFilter(new MockHttpServletRequest("GET", prefix + spelled), new MockHttpServletResponse(),
                    new MockFilterChain());
            org.mockito.ArgumentCaptor<com.example.perfume_budget.model.SiteVisit> saved =
                    org.mockito.ArgumentCaptor.forClass(com.example.perfume_budget.model.SiteVisit.class);
            verify(repo).save(saved.capture());
            assertThat(saved.getValue().getPage()).contains("[redacted]").doesNotContain(token)
                    .doesNotContain(token.substring(10, 30));
        }
    }

    @org.junit.jupiter.api.Test
    void skeletonFoldsLookAlikesAndDropsInvisibles() {
        assertThat(SiteVisitFilter.skeleton("w\u200Bebhook")).isEqualTo("webhook");
        assertThat(SiteVisitFilter.skeleton("\uFF57ebhook")).isEqualTo("webhook");
        assertThat(SiteVisitFilter.skeleton("W\u0435BH\u03BFOK")).isEqualTo("webhook");
        assertThat(SiteVisitFilter.skeleton("caf\u00e9")).isEqualTo("cafe");
        assertThat(SiteVisitFilter.resemblesWebhook("/api/v1/products")).isFalse();
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
