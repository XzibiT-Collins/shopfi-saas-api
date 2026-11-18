package com.example.perfume_budget.exception.handlers;


import com.example.perfume_budget.dto.CustomApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Spring Security's own text for the usual case (an anonymous call to a protected route), now always. */
    public static final String AUTHENTICATION_REQUIRED = "Full authentication is required to access this resource";

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        log.error("Unauthorized error: {} at path: {}", authException.getClass().getSimpleName(), request.getRequestURI());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // SEC-01d: fixed text; an AuthenticationException's message can quote the presented credential or a provider's answer
        CustomApiResponse<?> errorResponse = CustomApiResponse.error(AUTHENTICATION_REQUIRED);

        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
