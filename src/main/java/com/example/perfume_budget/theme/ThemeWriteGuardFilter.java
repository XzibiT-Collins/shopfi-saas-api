package com.example.perfume_budget.theme;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * Write guard for {@code PUT /api/v1/theme} (THEME-04c), placed in the Spring Security chain AFTER the
 * authorization filter (so only an authenticated ADMIN reaches it) and therefore BEFORE
 * {@code DispatcherServlet}, which is where Spring's multipart resolver parses and Tomcat spools the body to disk.
 * <ol>
 *   <li><b>Rate limit</b> (30/min/admin, contract 7.4): a throttled request is answered 429 without its body being
 *       read, so it costs no disk and no thread time.</li>
 *   <li><b>Multipart parsing under our control</b>: {@code MultipartException} for a missing boundary or a broken or
 *       truncated body is raised by Spring/Tomcat inside {@code DispatcherServlet.checkMultipart}, before any
 *       handler exists, so a controller-scoped advice can never map it (it fell through to the global 500). Parsing
 *       here (the parts are cached on the request, the later resolver reuses them) lets us answer the contract
 *       shapes: 400 {@code INVALID_FORMAT} for malformed/truncated bodies, 413 when a size limit is hit. This
 *       applies to the theme endpoint only; other endpoints keep their existing behaviour.</li>
 * </ol>
 * Not a Spring bean (it would also be auto-registered as a servlet filter): added to the security chain explicitly.
 */
@Slf4j
public class ThemeWriteGuardFilter extends OncePerRequestFilter {

    /** Request attribute carrying the HTTP status chosen for a multipart parse failure (see the error-dispatch filter). */
    static final String FAILURE_ATTR = ThemeWriteGuardFilter.class.getName() + ".multipartFailure";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThemeWriteRateLimiter rateLimiter;

    public ThemeWriteGuardFilter(ThemeWriteRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"PUT".equals(request.getMethod()) || !ThemeRequestSizeFilter.isThemePath(request);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            chain.doFilter(request, response); // authorization already decided; never charge an unauthenticated caller
            return;
        }
        TenantIdentity id = TenantContext.get();
        long retryAfter = rateLimiter.tryAcquire((id == null ? "-" : String.valueOf(id.slug())) + ":" + auth.getName());
        if (retryAfter > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            reject(response, 429, "Too many theme updates; retry later", null);
            return;
        }

        if (!isFormData(request)) {
            chain.doFilter(request, response);
            return;
        }
        // A missing boundary is rejected by the container's parser (IOFileUploadException "no multipart boundary")
        // and mapped to 400 below; no separate header check, so MockMvc-built multipart requests behave as before.
        Collection<Part> parts;
        try {
            parts = request.getParts();
        } catch (IllegalStateException | IOException | ServletException e) {
            // Includes a body shorter than its Content-Length (read timeout) and over-limit bodies.
            boolean tooLarge = isSizeLimit(e);
            log.debug("Theme multipart rejected: {}", e.getClass().getSimpleName());
            // After a failed body READ (timeout, reset) the servlet container discards what we write and dispatches
            // to the error page instead (anonymous /error would then answer a misleading 401). Record the intended
            // answer; ThemeMultipartErrorDispatchFilter replays it on that ERROR dispatch.
            request.setAttribute(FAILURE_ATTR, tooLarge ? 413 : 400);
            if (tooLarge) {
                reject(response, 413, "Request exceeds the 12 MB limit", null);
            } else {
                reject(response, 400, "Theme validation failed", malformed());
            }
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            for (Part p : parts) {
                try {
                    p.delete();
                } catch (IOException ignored) {
                    // Tomcat also deletes spooled parts when the request is recycled.
                }
            }
        }
    }

    static List<ThemeFieldError> malformed() {
        return List.of(new ThemeFieldError("$", "INVALID_FORMAT", "malformed multipart request"));
    }

    private static boolean isFormData(HttpServletRequest request) {
        String ct = request.getContentType();
        if (ct == null || ct.isBlank()) {
            return false;
        }
        try {
            return MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(MediaType.parseMediaType(ct));
        } catch (InvalidMediaTypeException e) {
            return false; // the controller answers 415
        }
    }

    private static boolean isSizeLimit(Throwable t) {
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.equals("SizeLimitExceededException") || n.equals("FileSizeLimitExceededException")) {
                return true;
            }
        }
        return false;
    }

    static <T> void reject(HttpServletResponse response, int status, String message, T data) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Connection", "close");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            MAPPER.writeValue(response.getOutputStream(), data == null
                    ? CustomApiResponse.error(message) : CustomApiResponse.error(message, data));
            // Commit now: after a failed body read the container is in an error state and would otherwise replace an
            // uncommitted response with its /error dispatch (which is anonymous and answers a misleading 401).
            response.flushBuffer();
        } catch (IOException e) {
            log.debug("Could not write theme rejection ({}): {}", status, e.getClass().getSimpleName());
        }
    }
}
