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
import org.springframework.context.annotation.Bean;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .addFilterBefore(new com.example.perfume_budget.security.cors.CanonicalPathGuardFilter(), org.springframework.web.filter.CorsFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/dev/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/category/**").permitAll()
                        .requestMatchers("/api/v1/product/**").permitAll()
                        .requestMatchers("/api/v1/webhook/**").permitAll()
                        // Platform control plane: auth endpoints and public onboarding are open; everything else
                        // under /platform needs a platform-claim SUPER_ADMIN token (JWTFilter.authenticatePlatform).
                        .requestMatchers("/api/v1/platform/auth/me").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/v1/platform/auth/**").permitAll()
                        .requestMatchers("/api/v1/platform/onboarding/**").permitAll()
                        .requestMatchers("/api/v1/platform/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
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
                .addFilterBefore(tenantResolutionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new TenantRequiredFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(siteVisitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .userDetailsService(userDetailsService)
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                );
        return http.build();
    }
}
