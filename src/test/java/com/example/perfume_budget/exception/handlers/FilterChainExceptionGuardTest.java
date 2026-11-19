package com.example.perfume_budget.exception.handlers;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.coyote.BadRequestException;
import org.apache.coyote.CloseNowException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-01e (Info): the outermost filter catches what would otherwise reach Tomcat's {@code StandardWrapperValve}
 * (logged there at ERROR with every message of the cause chain), logs it like {@code GlobalExceptionHandler} (class
 * names and frames only) and answers 500 through the container's error page, as Tomcat would have. Exceptions Tomcat
 * itself logs only at DEBUG (a client abort, a bad request, close-now, or a ServletException whose root cause is a bad
 * request) pass through unchanged. On a committed response the guard throws a CloseNowException after logging, so the
 * client sees the truncation (rework 1, F-1).
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

    /**
     * Rework 1 (F-1): part of the body is already on the wire. Swallowing the failure would let Tomcat finish the
     * chunked body, so a truncated answer would look complete; a CloseNowException (no text, no cause) makes Tomcat
     * close the connection instead, logging it at DEBUG only.
     */
    @Test
    void aCommittedResponseIsClosedNotCompleted(CapturedOutput output) throws Exception {
        response.setStatus(200);
        response.flushBuffer();
        assertThatThrownBy(() -> guard.doFilter(request, response, failingWith(new IllegalStateException(SECRET))))
                .isExactlyInstanceOf(CloseNowException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("guard-leak@x.test").doesNotContain("Key ("));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain("guard-leak@x.test")
                .contains("Request failed outside the controllers").contains("java.lang.IllegalStateException");
    }

    @Test
    void aCommittedResponseOnAnErrorDispatchIsClosedToo(CapturedOutput output) {
        request.setDispatcherType(DispatcherType.ERROR);
        response.setCommitted(true);
        assertThatThrownBy(() -> guard.doFilter(request, response, failingWith(new IOException(SECRET))))
                .isExactlyInstanceOf(CloseNowException.class).hasNoCause();
        assertThat(output.getAll()).doesNotContain("guard-leak@x.test").contains("java.io.IOException");
    }

    /** FIX-A2 (re-review N01): an async dispatch writes the rest of a streamed answer, so a failure there is the F-1 case too. */
    @Test
    void aCommittedResponseOnAnAsyncDispatchIsClosedToo(CapturedOutput output) {
        request.setDispatcherType(DispatcherType.ASYNC);
        response.setCommitted(true);
        assertThatThrownBy(() -> guard.doFilter(request, response, failingWith(new IllegalStateException(SECRET))))
                .isExactlyInstanceOf(CloseNowException.class).hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("guard-leak@x.test"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain("guard-leak@x.test").contains("java.lang.IllegalStateException");
    }

    @Test
    void anUncommittedAsyncDispatchIsAnsweredWithTheErrorPage(CapturedOutput output) throws Exception {
        request.setDispatcherType(DispatcherType.ASYNC);
        guard.doFilter(request, response, failingWith(new IllegalStateException(SECRET)));
        assertSafe500(output, "java.lang.IllegalStateException");
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
                new CloseNowException(SECRET), new ServletException("x", new ClientAbortException(SECRET)),
                new ServletException("x", new RuntimeException("y", new BadRequestException(SECRET)))}) {
            assertThatThrownBy(() -> guard.doFilter(request, response, failingWith(e))).isSameAs(e);
        }
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Rework 1 (F-2): Tomcat walks the cause chain only for a ServletException, and then only to its ROOT cause. Any
     * other exception wrapping a client abort, or a ServletException whose root cause is not a bad request, it logs at
     * ERROR with its message, so the guard must handle it.
     */
    static Stream<Arguments> whatTomcatLogsAtError() {
        return Stream.of(
                Arguments.of("rte-wrapping-abort", new UncheckedIOException(SECRET, new ClientAbortException("abort"))),
                Arguments.of("ioe-wrapping-abort", new IOException(SECRET, new ClientAbortException("abort"))),
                Arguments.of("rte-wrapping-bad-request", new IllegalStateException(SECRET, new BadRequestException("bad"))),
                Arguments.of("ioe-wrapping-close-now", new IOException(SECRET, new CloseNowException("close"))),
                Arguments.of("servlet-wrapping-close-now", new ServletException(SECRET, new CloseNowException("close"))),
                Arguments.of("servlet-abort-not-root",
                        new ServletException(SECRET, new ClientAbortException(new IOException("Broken pipe")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("whatTomcatLogsAtError")
    void anythingElseWrappingAClientAbortIsHandledByTheGuard(String name, Exception e, CapturedOutput output)
            throws Exception {
        guard.doFilter(request, response, failingWith(e));
        assertSafe500(output, e.getClass().getName());
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
