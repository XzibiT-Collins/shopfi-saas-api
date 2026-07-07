package com.example.perfume_budget.security;

import com.example.perfume_budget.exception.handlers.CustomAccessDeniedHandler;
import com.example.perfume_budget.exception.handlers.JwtAuthenticationEntryPoint;
import com.example.perfume_budget.filter.JWTFilter;
import com.example.perfume_budget.filter.SiteVisitFilter;
import com.example.perfume_budget.security.oauth2.CustomOAuth2UserService;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationFailureHandler;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationSuccessHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
                mock(SiteVisitFilter.class)
        );
    }

    @Test
    void corsConfigurationSourceUsesCsvValuesFromInjectedProperties() {
        ReflectionTestUtils.setField(securityConfig, "allowedOrigins", "http://localhost:3000, https://example.com ");
        ReflectionTestUtils.setField(securityConfig, "allowedMethods", "GET,POST, OPTIONS");
        ReflectionTestUtils.setField(securityConfig, "allowedHeaders", "Authorization, Content-Type");

        CorsConfiguration configuration = corsConfigurationSource().getCorsConfiguration(
                new MockHttpServletRequest("GET", "/api/v1/auth/me")
        );

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(
                "http://localhost:3000",
                "https://example.com"
        );
        assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "OPTIONS");
        assertThat(configuration.getAllowedHeaders()).containsExactly("Authorization", "Content-Type");
        assertThat(configuration.getAllowCredentials()).isTrue();
    }

    @Test
    void corsConfigurationSourceKeepsWildcardDefaultsIntact() {
        ReflectionTestUtils.setField(securityConfig, "allowedOrigins",
                "http://localhost:3000,http://localhost:8080,http://localhost:4200,https://perfume-budget-gs-fe.vercel.app");
        ReflectionTestUtils.setField(securityConfig, "allowedMethods", "*");
        ReflectionTestUtils.setField(securityConfig, "allowedHeaders", "*");

        CorsConfiguration configuration = corsConfigurationSource().getCorsConfiguration(
                new MockHttpServletRequest("OPTIONS", "/any/path")
        );

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(
                "http://localhost:3000",
                "http://localhost:8080",
                "http://localhost:4200",
                "https://perfume-budget-gs-fe.vercel.app"
        );
        assertThat(configuration.getAllowedMethods()).containsExactly("*");
        assertThat(configuration.getAllowedHeaders()).containsExactly("*");
    }

    private UrlBasedCorsConfigurationSource corsConfigurationSource() {
        return (UrlBasedCorsConfigurationSource) securityConfig.corsConfigurationSource();
    }
}
