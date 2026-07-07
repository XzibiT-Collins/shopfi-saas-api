package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.analytics.*;
import com.example.perfume_budget.enums.*;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.repository.SiteVisitRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.service.interfaces.AdminMetricService;
import com.example.perfume_budget.utils.AuthCookieUtil;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminMetricController.class)
@ActiveProfiles("test")
@Import(SalesAnalyticsControllerTest.MethodSecurityConfig.class)
class SalesAnalyticsControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean
        MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
            return new DefaultMethodSecurityExpressionHandler();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AdminMetricService adminMetricService;

    // Security infrastructure mocks — not part of the feature under test,
    // but required so @WebMvcTest can wire SecurityConfig without a real DB or Redis.
    @MockBean
    UserRepository userRepository;

    @MockBean
    SiteVisitRepository siteVisitRepository;

    @MockBean
    JWTUtil jwtUtil;

    @MockBean
    AuthCookieUtil authCookieUtil;

    @MockBean
    UserDetailsService userDetailsService;

    @MockBean
    JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private SalesAnalyticsResponse emptyResponse() {
        return new SalesAnalyticsResponse(List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void defaults_returnOk() throws Exception {
        when(adminMetricService.getSalesAnalytics(
                eq(SalesAnalyticsSource.ALL), eq(SalesAnalyticsGranularity.DAY),
                isNull(), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revenueBreakdown").isArray())
                .andExpect(jsonPath("$.data.profitBreakdown").isArray())
                .andExpect(jsonPath("$.data.miniStats").isArray())
                .andExpect(jsonPath("$.data.orderStatus").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void explicitSourceAndGranularity_returnOk() throws Exception {
        when(adminMetricService.getSalesAnalytics(
                eq(SalesAnalyticsSource.ONLINE), eq(SalesAnalyticsGranularity.WEEK),
                isNull(), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("source", "ONLINE")
                        .param("granularity", "WEEK"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validFromAndTo_returnOk() throws Exception {
        when(adminMetricService.getSalesAnalytics(
                eq(SalesAnalyticsSource.ALL), eq(SalesAnalyticsGranularity.DAY),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 3, 31))))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void onlyFrom_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("from", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void onlyTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics")
                        .param("from", "2026-03-01")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/sales-analytics"))
                .andExpect(status().isForbidden());
    }
}
