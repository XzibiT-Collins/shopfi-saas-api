package com.example.perfume_budget.filter;

import com.example.perfume_budget.repository.SiteVisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * VIEW-01 / FIX-C: the view beacon path never reaches site_visits, whatever the HTTP method: a caller chosen slug plus the
 * address must not be stored by a GET, PUT, OPTIONS ... either. Neighbouring paths are still visits.
 */
class SiteVisitFilterViewBeaconTest {

    private final SiteVisitFilter filter = new SiteVisitFilter(mock(SiteVisitRepository.class));

    private boolean skipped(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setServletPath(uri);
        return filter.shouldNotFilter(req);
    }

    @Test
    void everyMethodOnTheBeaconPathIsExcluded() {
        for (String method : List.of("POST", "GET", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD", "TRACE")) {
            assertThat(skipped(method, "/api/v1/product/oud-royal-1a2b3c4d/view")).as(method).isTrue();
            assertThat(skipped(method, "/api/v1/product/oud-royal-1a2b3c4d/view/")).as(method + " trailing slash").isTrue();
            assertThat(skipped(method, "/api/v1/product/attacker-chosen-slug-%41/view")).as(method + " encoded").isTrue();
        }
    }

    @Test
    void theProductPageAndOtherPathsAreStillVisits() {
        assertThat(skipped("GET", "/api/v1/product/oud-royal-1a2b3c4d")).isFalse();
        assertThat(skipped("GET", "/api/v1/product/listing")).isFalse();
        assertThat(skipped("GET", "/api/v1/product/by-ids")).isFalse();
        assertThat(skipped("POST", "/api/v1/product/a/view/extra")).isFalse();
        assertThat(skipped("POST", "/api/v1/product/view")).isFalse();
    }
}
