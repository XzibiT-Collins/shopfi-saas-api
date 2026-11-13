package com.example.perfume_budget.theme;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Replays {@link ThemeWriteGuardFilter}'s answer on the container's ERROR dispatch (THEME-04c). When the request
 * body cannot be read (client stalls past the connector read timeout, or resets), Tomcat puts the exchange in an error
 * state, silently drops whatever the application wrote and dispatches to {@code /error}; that path is anonymous, so
 * without this filter the client would receive a misleading 401 instead of the contract 400/413. Only requests the
 * guard marked are touched; every other ERROR dispatch behaves exactly as before. First in the security chain.
 */
public class ThemeMultipartErrorDispatchFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return request.getDispatcherType() != DispatcherType.ERROR
                || !(request.getAttribute(ThemeWriteGuardFilter.FAILURE_ATTR) instanceof Integer);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        int status = (Integer) request.getAttribute(ThemeWriteGuardFilter.FAILURE_ATTR);
        if (status == 413) {
            ThemeWriteGuardFilter.reject(response, 413, "Request exceeds the 12 MB limit", null);
        } else {
            ThemeWriteGuardFilter.reject(response, 400, "Theme validation failed", ThemeWriteGuardFilter.malformed());
        }
    }
}
