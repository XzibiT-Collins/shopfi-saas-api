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
            "/API/V1/Webhook/SECRET-TOKEN-123", "/api/v1/webhook;x=1/SECRET-TOKEN-123"})
    void webhookPathsAreNeverRecorded(String uri) throws Exception {
        SiteVisitRepository repo = mock(SiteVisitRepository.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        MockFilterChain chain = new MockFilterChain();

        new SiteVisitFilter(repo).doFilter(req, new MockHttpServletResponse(), chain);

        verifyNoInteractions(repo);
        assertThat(chain.getRequest()).as("the request still goes through").isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/products", "/api/v1/webhooks-info", "/api/v1/webhookx/a"})
    void otherPathsAreStillRecorded(String uri) throws Exception {
        SiteVisitRepository repo = mock(SiteVisitRepository.class);
        new SiteVisitFilter(repo).doFilter(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse(),
                new MockFilterChain());
        verify(repo).save(any());
    }
}
