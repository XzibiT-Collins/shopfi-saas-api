package com.example.perfume_budget.exception.handlers;

import com.example.perfume_budget.utils.SafeStackTrace;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.apache.coyote.CloseNowException;

import java.io.IOException;

/**
 * SEC-01e (Info): an exception that escapes a servlet filter (tenant resolution, JWT, Spring Security, ...) or the
 * {@code DispatcherServlet} itself never reaches {@code GlobalExceptionHandler}. Tomcat's {@code StandardWrapperValve}
 * would log it at ERROR with the message of every cause ("Servlet.service() for servlet [dispatcherServlet] ... threw
 * exception [..]"), and a database or provider message can quote personal data. This outermost filter catches it
 * first, logs the class names and frames only ({@link SafeStackTrace}, the full detail at DEBUG) and hands the answer
 * to the container's error page exactly as Tomcat would have ({@code sendError(500)}; the body is Spring Boot's error
 * JSON, which never carries a message). Note: that {@code /error} dispatch is anonymous, so today it answers 401, with
 * or without this filter.
 *
 * <p>Left to Tomcat unchanged: what it logs only at DEBUG (a client abort, a bad request, close-now, also as the root
 * cause of a {@code ServletException}), and {@link Error}s (out of memory, stack overflow) which must not be swallowed.
 * On an ERROR dispatch the status is set without another error page; a committed response is left as it is.
 */
@Slf4j
public class FilterChainExceptionGuard implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (tomcatLogsOnlyAtDebug(e)) {
                throw e;
            }
            log.error("Request failed outside the controllers: {}", SafeStackTrace.of(e));
            log.debug("Request failure detail", e);
            if (!response.isCommitted() && response instanceof HttpServletResponse http) {
                if (request.getDispatcherType() == DispatcherType.ERROR) {
                    http.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                } else {
                    http.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }
        }
    }

    /** Tomcat's StandardWrapperValve logs these at DEBUG only (ClientAbortException is a BadRequestException). */
    static boolean tomcatLogsOnlyAtDebug(Exception e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof BadRequestException || t instanceof CloseNowException) {
                return true;
            }
        }
        return false;
    }
}
