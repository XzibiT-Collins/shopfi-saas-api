package com.example.perfume_budget.security;


import com.example.perfume_budget.exception.handlers.CustomAccessDeniedHandler;
import com.example.perfume_budget.exception.handlers.JwtAuthenticationEntryPoint;
import com.example.perfume_budget.filter.JWTFilter;
import com.example.perfume_budget.filter.SiteVisitFilter;
import com.example.perfume_budget.security.cors.DynamicTenantCorsConfigurationSource;
import com.example.perfume_budget.tenancy.TenantRequiredFilter;
import com.example.perfume_budget.tenancy.TenantResolutionFilter;
import com.example.perfume_budget.security.oauth2.CustomOAuth2UserService;
import com.example.perfume_budget.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationFailureHandler;
import com.example.perfume_budget.security.oauth2.OAuth2AuthenticationSuccessHandler;
import com.example.perfume_budget.security.oauth2.TenantAwareAuthorizationRequestResolver;
import lombok.RequiredArgsConstructor;
import com.example.perfume_budget.theme.ThemeRequestSizeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;



@RequiredArgsConstructor
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JWTFilter jwtFilter;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final UserDetailsService userDetailsService;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final SiteVisitFilter siteVisitFilter;
    private final TenantResolutionFilter tenantResolutionFilter;
    private final TenantAwareAuthorizationRequestResolver authorizationRequestResolver;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository;
    private final DynamicTenantCorsConfigurationSource corsConfigurationSource;

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, com.example.perfume_budget.theme.ThemeWriteRateLimiter themeWriteRateLimiter,
                                                   @org.springframework.beans.factory.annotation.Value("${tenant.dev-endpoints.enabled:false}") boolean devEndpointsEnabled) throws Exception {
        http
                .addFilterBefore(new com.example.perfume_budget.security.cors.CanonicalPathGuardFilter(), org.springframework.web.filter.CorsFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SEC-03: deny by default. Only the explicit, method-specific allow-list below is public;
                        // everything else needs authentication (anyRequest) and, where listed, a role. Controller
                        // methods ALSO carry @PreAuthorize (defence in depth; AuthorizationMatrixStructureTest enforces it).
                        // Dev tenant trigger: open only where the controller bean exists (dev); denyAll everywhere else.
                        .requestMatchers("/api/v1/dev/**").access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(devEndpointsEnabled))
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/verify-otp",
                                "/api/v1/auth/verify-login-otp", "/api/v1/auth/resend-otp", "/api/v1/auth/resend-login-otp",
                                "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers("/api/v1/auth/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/theme").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/theme").hasRole("ADMIN")
                        // Catalog: storefront reads are public; every other category/product route is ADMIN
                        // (FRONT_DESK may also search the admin product list).
                        .requestMatchers(HttpMethod.GET, "/api/v1/category/all").permitAll()
                        .requestMatchers("/api/v1/category/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/product/admin/search").hasAnyRole("ADMIN", "FRONT_DESK")
                        .requestMatchers(HttpMethod.GET, "/api/v1/product/featured", "/api/v1/product/listing",
                                "/api/v1/product/search", "/api/v1/product/*").permitAll()
                        .requestMatchers("/api/v1/product/**").hasRole("ADMIN")
                        // Paystack webhook: unauthenticated by design, authenticated by per-tenant token + HMAC signature.
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhook/*").permitAll()
                        // Platform control plane: auth endpoints and public onboarding are open; everything else
                        // under /platform needs a platform-claim SUPER_ADMIN token (JWTFilter.authenticatePlatform).
                        .requestMatchers(HttpMethod.GET, "/api/v1/platform/auth/me").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/auth/login", "/api/v1/platform/auth/refresh",
                                "/api/v1/platform/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/onboarding/signup",
                                "/api/v1/platform/onboarding/verify", "/api/v1/platform/onboarding/resend").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/platform/onboarding/status").permitAll()
                        .requestMatchers("/api/v1/platform/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .baseUri("/oauth2/authorize")
                                .authorizationRequestResolver(authorizationRequestResolver)
                                .authorizationRequestRepository(cookieAuthorizationRequestRepository)
                        )
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/oauth2/callback/*")
                        )
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler)
                )
                .addFilterBefore(new com.example.perfume_budget.theme.ThemeMultipartErrorDispatchFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantResolutionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new TenantRequiredFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ThemeRequestSizeFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(siteVisitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // THEME-04c: after authentication AND authorization, before DispatcherServlet's multipart parsing/spooling.
                .addFilterAfter(new com.example.perfume_budget.theme.ThemeWriteGuardFilter(themeWriteRateLimiter),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .userDetailsService(userDetailsService)
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                );
        return http.build();
    }
}
