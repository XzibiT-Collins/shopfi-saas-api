package com.example.perfume_budget.exception.handlers;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.coyote.BadRequestException;
import org.apache.coyote.CloseNowException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-01e (Info): the outermost filter catches what would otherwise reach Tomcat's {@code StandardWrapperValve}
 * (logged there at ERROR with every message of the cause chain), logs it like {@code GlobalExceptionHandler} (class
 * names and frames only) and answers 500 through the container's error page, as Tomcat would have. Exceptions Tomcat
 * itself logs only at DEBUG (a client abort, a bad request, close-now) pass through unchanged.
 */
@ExtendWith(OutputCaptureExtension.class)
class FilterChainExceptionGuardTest {

    static final String SECRET = "Key (email)=(guard-leak@x.test) already exists";

    private final FilterChainExceptionGuard guard = new FilterChainExceptionGuard();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static FilterChain failingWith(Exception e) {
        return (req, res) -> {
            if (e instanceof IOException io) throw io;
            if (e instanceof ServletException se) throw se;
            throw (RuntimeException) e;
        };
    }

    private void assertSafe500(CapturedOutput output, String exceptionClass) {
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getErrorMessage()).isNull(); // the error page renders the body, without any message
        assertThat(output.getAll()).doesNotContain("guard-leak@x.test")
                .contains("Request failed outside the controllers").contains(exceptionClass);
    }

    @Test
    void aRuntimeExceptionIsLoggedWithoutItsMessageAndAnswered500(CapturedOutput output) throws Exception {
        guard.doFilter(request, response, failingWith(new IllegalStateException("x", new RuntimeException(SECRET))));
        assertSafe500(output, "java.lang.IllegalStateException");
        assertThat(output.getAll()).contains("java.lang.RuntimeException"); // the cause's class is there, not its text
    }

    @Test
    void aServletExceptionIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        guard.doFilter(request, response, failingWith(new ServletException("Request processing failed: " + SECRET)));
        assertSafe500(output, "jakarta.servlet.ServletException");
    }

    @Test
    void anIoExceptionIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        guard.doFilter(request, response, failingWith(new IOException(SECRET)));
        assertSafe500(output, "java.io.IOException");
    }

    @Test
    void aCommittedResponseIsLeftAsItIs(CapturedOutput output) throws Exception {
        response.setStatus(200);
        response.flushBuffer();
        guard.doFilter(request, response, failingWith(new IllegalStateException(SECRET)));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain("guard-leak@x.test").contains("Request failed outside the controllers");
    }

    @Test
    void onAnErrorDispatchTheStatusIsSetWithoutAnotherErrorPage(CapturedOutput output) throws Exception {
        request.setDispatcherType(DispatcherType.ERROR);
        guard.doFilter(request, response, failingWith(new IllegalStateException(SECRET)));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.isCommitted()).isFalse(); // setStatus, not sendError (no error page inside the error page)
        assertThat(output.getAll()).doesNotContain("guard-leak@x.test");
    }

    @Test
    void whatTomcatLogsOnlyAtDebugPassesThrough() {
        for (Exception e : new Exception[]{new ClientAbortException(SECRET), new BadRequestException(SECRET),
                new CloseNowException(SECRET), new ServletException("x", new ClientAbortException(SECRET))}) {
            assertThatThrownBy(() -> guard.doFilter(request, response, failingWith(e))).isSameAs(e);
        }
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void anErrorIsNotCaught() {
        StackOverflowError error = new StackOverflowError();
        assertThatThrownBy(() -> guard.doFilter(request, response, (req, res) -> {
            throw error;
        })).isSameAs(error);
    }

    @Test
    void aRequestThatSucceedsIsUntouched(CapturedOutput output) throws Exception {
        guard.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(output.getAll()).doesNotContain("Request failed outside the controllers");
    }

    @Test
    void itIsRegisteredOutermostForRequestAsyncAndErrorDispatches() {
        var bean = new FilterChainExceptionGuardConfig().filterChainExceptionGuard();
        assertThat(bean.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(bean.getFilter()).isInstanceOf(FilterChainExceptionGuard.class);
        assertThat(bean.getUrlPatterns()).isEmpty(); // every path
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(bean, "dispatcherTypes"))
                .isEqualTo(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
    }
}
