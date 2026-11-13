package com.example.perfume_budget.theme;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Request-layer size gate for writes to {@code /api/v1/theme} (contract 5.2 / 7.4): a declared
 * {@code Content-Length} over the limit (JSON 64 KB, multipart 12 MB) is answered 413 before the body is read,
 * parsed or spooled. Bodies with no declared length are bounded later (JSON: bounded read in the controller;
 * multipart: Tomcat's {@code max-request-size} aborts the stream). Not a Spring bean: added to the security chain
 * explicitly (like TenantRequiredFilter) after tenant resolution and before authentication.
 */
public class ThemeRequestSizeFilter extends OncePerRequestFilter {

    static final String PATH = "/api/v1/theme";
    public static final long MAX_JSON_BYTES = 64L * 1024;
    public static final long MAX_MULTIPART_BYTES = 12L * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String m = request.getMethod();
        return !request.getRequestURI().startsWith(PATH)
                || "GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String ct = request.getContentType();
        boolean multipart = ct != null && ct.regionMatches(true, 0, "multipart/", 0, "multipart/".length());
        long limit = multipart ? MAX_MULTIPART_BYTES : MAX_JSON_BYTES;
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Connection", "close");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            MAPPER.writeValue(response.getOutputStream(), CustomApiResponse.error(
                    multipart ? "Request exceeds the 12 MB limit" : "Theme document exceeds the 64 KB limit"));
            return;
        }
        chain.doFilter(request, response);
    }
}
