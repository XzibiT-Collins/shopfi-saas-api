package com.example.perfume_budget.security.cors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First filter in the security chain (before CORS): answers 400 for an encoded, semicolon or double-slash variant of
 * a {@code /api/v1/platform} path (for example {@code /api/v1/%70latform/auth/login}), so no zone decision (CORS,
 * tenant scope, token handling) can ever be taken on a path the router reads differently. Deliberately NOT a bean
 * (registered only in the security chain) so the servlet container does not also run it.
 */
public class CanonicalPathGuardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (RequestPaths.isObfuscatedPlatformPath(request)) {
            // setStatus, not sendError: the container error dispatch to /error would be re-authenticated into a 401
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"description\":\"Invalid path\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
