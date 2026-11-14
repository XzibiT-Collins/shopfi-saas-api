package com.example.perfume_budget.security;

import com.example.perfume_budget.exception.handlers.CustomAccessDeniedHandler;
import com.example.perfume_budget.exception.handlers.JwtAuthenticationEntryPoint;
import com.example.perfume_budget.filter.JWTFilter;
import com.example.perfume_budget.filter.SiteVisitFilter;
import com.example.perfume_budget.security.cors.DynamicTenantCorsConfigurationSource;
import com.example.perfume_budget.security.oauth2.CustomOAuth2UserService;
import com.example.perfume_budget.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationFailureHandler;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationSuccessHandler;
import com.example.perfume_budget.security.oauth2.TenantAwareAuthorizationRequestResolver;
import com.example.perfume_budget.tenancy.TenantResolutionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.springframework.security.core.userdetails.UserDetailsService;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig(
                mock(JWTFilter.class),
                mock(CustomAccessDeniedHandler.class),
                mock(JwtAuthenticationEntryPoint.class),
                mock(UserDetailsService.class),
                mock(CustomOAuth2UserService.class),
                mock(OAuth2AuthenticationSuccessHandler.class),
                mock(OAuth2AuthenticationFailureHandler.class),
                mock(SiteVisitFilter.class),
                mock(TenantResolutionFilter.class),
                mock(TenantAwareAuthorizationRequestResolver.class),
                mock(HttpCookieOAuth2AuthorizationRequestRepository.class),
                mock(DynamicTenantCorsConfigurationSource.class)
        );
    }

    @Test
    void constructsWithTheDynamicCorsSource() {
        // The static cors.allowed-origins bean and its two CSV/wildcard tests were replaced by
        // DynamicTenantCorsConfigurationSource (see TenantOriginPolicyTest and DynamicCorsIntegrationTest).
        assertThat(securityConfig).isNotNull();
    }
}
