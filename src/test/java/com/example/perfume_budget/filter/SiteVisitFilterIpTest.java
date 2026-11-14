package com.example.perfume_budget.filter;

import com.example.perfume_budget.model.SiteVisit;
import com.example.perfume_budget.repository.SiteVisitRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** PROV-02b AC1: SiteVisitFilter must not trust a client supplied X-Forwarded-For. */
class SiteVisitFilterIpTest {

    @Test
    void recordsTheContainerResolvedAddress_notAnyClientSuppliedHeader() throws Exception {
        SiteVisitRepository repo = mock(SiteVisitRepository.class);
        SiteVisitFilter filter = new SiteVisitFilter(repo);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        req.setRemoteAddr("203.0.113.9");
        req.addHeader("X-Forwarded-For", "6.6.6.6, 7.7.7.7");
        req.addHeader("X-Real-IP", "8.8.8.8");
        req.addHeader("Forwarded", "for=9.9.9.9");

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<SiteVisit> saved = ArgumentCaptor.forClass(SiteVisit.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getIpAddress()).isEqualTo("203.0.113.9");
        verify(repo).existsByIpAddressAndVisitDateAndPage(anyString(), any(), anyString());
    }
}
