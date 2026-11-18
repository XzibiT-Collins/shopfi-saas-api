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
import org.apache.catalina.core.StandardWrapper;
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
 * <p>Left to Tomcat unchanged: what it logs only at DEBUG (a client abort, a bad request, close-now, or a
 * {@code ServletException} whose root cause is a bad request / client abort), and {@link Error}s (out of memory, stack
 * overflow) which must not be swallowed. On an ERROR dispatch the status is set without another error page.
 *
 * <p>SEC-01e rework 1 (F-1): when the response is already committed (part of the body is on the wire) the failure must
 * still reach the client. Swallowing it would let Tomcat end the chunked body normally, so a truncated answer would look
 * complete. After logging, a {@link CloseNowException} without any text or cause is thrown instead: Tomcat logs that
 * only at DEBUG and closes the connection without the closing chunk, as it did before this filter existed.
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
            if (response.isCommitted()) {
                throw new CloseNowException("Request failed after the response was committed");
            }
            if (response instanceof HttpServletResponse http) {
                if (request.getDispatcherType() == DispatcherType.ERROR) {
                    http.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                } else {
                    http.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }
        }
    }

    /**
     * Exactly what Tomcat 10.1's {@code StandardWrapperValve} logs at DEBUG only: a {@link BadRequestException}
     * (a {@code ClientAbortException} is one) or a {@link CloseNowException} thrown as such, or a
     * {@link ServletException} whose root cause (its deepest cause, Tomcat's own {@link StandardWrapper#getRootCause})
     * is a {@link BadRequestException}. Anything else, such as an {@code IOException} or a {@code RuntimeException}
     * wrapping a client abort, Tomcat logs at ERROR with its message, so the guard handles it.
     */
    static boolean tomcatLogsOnlyAtDebug(Exception e) {
        if (e instanceof BadRequestException || e instanceof CloseNowException) {
            return true;
        }
        if (e instanceof ServletException se) {
            return StandardWrapper.getRootCause(se) instanceof BadRequestException;
        }
        return false;
    }
}
