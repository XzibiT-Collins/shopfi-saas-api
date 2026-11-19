package com.example.perfume_budget.security;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-01e (Info) on a REAL Tomcat: an exception that escapes a servlet filter never reaches Tomcat's
 * {@code StandardWrapperValve}, which would log it at ERROR with its full message ("Servlet.service() for servlet
 * [dispatcherServlet] ... threw exception" plus the message of every cause), outside {@code GlobalExceptionHandler}.
 * The outermost {@code FilterChainExceptionGuard} logs the class names and frames only and leaves the answer to the
 * container's error page exactly as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
@Import(FilterExceptionLoggingIntegrationTest.ThrowingFilterConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class FilterExceptionLoggingIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String SECRET = "Key (email)=(filter-leak@x.test) already exists";
    static final String PATH = "/__sec01e-probe/";
    /** Under /ws/**: permitted to anonymous callers and exempt from tenant resolution, so it reaches the controller. */
    static final String COMMITTED_PATH = "/ws/__sec01e-probe/committed";

    @TestConfiguration(proxyBeanMethods = false)
    static class ThrowingFilterConfig {
        /** Before Spring Security, after the guard: fails like a tenant lookup whose database error quotes a row. */
        @Bean
        FilterRegistrationBean<Filter> sec01eThrowingFilter() {
            Filter filter = (req, res, chain) -> {
                String uri = ((HttpServletRequest) req).getRequestURI();
                if (uri.endsWith("/runtime")) {
                    throw new IllegalStateException("wrapped", new RuntimeException(SECRET));
                }
                if (uri.endsWith("/servlet")) {
                    throw new ServletException("Request processing failed: " + SECRET);
                }
                if (uri.endsWith("/io")) {
                    throw new IOException(SECRET);
                }
                // rework 1 (F-2): Tomcat logs these at ERROR with the message (only a ServletException's root cause counts)
                if (uri.endsWith("/rte-wrapping-abort")) {
                    throw new UncheckedIOException(SECRET, new ClientAbortException("abort"));
                }
                if (uri.endsWith("/ioe-wrapping-abort")) {
                    throw new IOException(SECRET, new ClientAbortException("abort"));
                }
                chain.doFilter(req, res);
            };
            FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(filter);
            bean.addUrlPatterns(PATH + "*");
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return bean;
        }

        @Bean
        CommittedThenFailingController sec01eCommittedThenFailingController() {
            return new CommittedThenFailingController();
        }
    }

    /** Rework 1 (F-1): a controller that streams 64 KB, flushes (the response is committed), then fails. */
    @RestController
    static class CommittedThenFailingController {
        @GetMapping("/ws/__sec01e-probe/complete")
        void complete(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            OutputStream out = response.getOutputStream();
            out.write(new byte[8 * 8192]);
            out.flush();
        }

        @GetMapping(COMMITTED_PATH)
        void committedThenFail(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            OutputStream out = response.getOutputStream();
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'a');
            for (int i = 0; i < 8; i++) {
                out.write(chunk);
            }
            out.flush();
            throw new IllegalStateException(SECRET);
        }
    }

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String kind) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + kind))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertSafelyLogged(CapturedOutput output, HttpResponse<String> response, String exceptionClass) {
        // SEC-01f: the ERROR dispatch is permitted, so the container answer (sendError 500) reaches the client as 500.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("filter-leak@x.test");
        String logged = output.getAll();
        assertThat(logged).doesNotContain("filter-leak@x.test").doesNotContain("Servlet.service()");
        assertThat(logged).contains("Request failed outside the controllers").contains(exceptionClass);
    }

    @Test
    void aRuntimeExceptionFromAFilterIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        assertSafelyLogged(output, get("runtime"), "java.lang.IllegalStateException");
    }

    @Test
    void aServletExceptionFromAFilterIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        assertSafelyLogged(output, get("servlet"), "jakarta.servlet.ServletException");
    }

    @Test
    void anIoExceptionFromAFilterIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        assertSafelyLogged(output, get("io"), "java.io.IOException");
    }

    @Test
    void aRequestThatDoesNotFailIsUntouched() throws Exception {
        // reaches Spring Security: anonymous on an unknown path, no exception, no guard involvement
        assertThat(get("fine").statusCode()).isNotEqualTo(500);
    }

    @Test
    void aRuntimeExceptionWrappingAClientAbortIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        assertSafelyLogged(output, get("rte-wrapping-abort"), "java.io.UncheckedIOException");
    }

    @Test
    void anIoExceptionWrappingAClientAbortIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        assertSafelyLogged(output, get("ioe-wrapping-abort"), "java.io.IOException");
    }

    /** What a plain socket read: the status line, the bytes after the headers, and whether the chunked body ended. */
    record RawAnswer(String statusLine, int bodyBytes, boolean chunkedBodyTerminated) {}

    private RawAnswer rawGet(String path) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try {
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    received.write(buffer, 0, n);
                }
            } catch (SocketException reset) {
                // a reset instead of a clean close also leaves the body unterminated; keep what arrived
            }
            String all = received.toString(StandardCharsets.ISO_8859_1);
            int headersEnd = all.indexOf("\r\n\r\n");
            assertThat(headersEnd).as("headers received").isPositive();
            assertThat(all.substring(0, headersEnd)).containsIgnoringCase("Transfer-Encoding: chunked");
            return new RawAnswer(all.substring(0, all.indexOf("\r\n")), all.length() - headersEnd - 4,
                    all.endsWith("\r\n0\r\n\r\n"));
        }
    }

    /**
     * Rework 1 (F-1): the controller has already sent 64 KB when it fails, so the status cannot change. The client must
     * still see that the answer is incomplete: the connection is closed WITHOUT the closing zero-length chunk (as on a
     * Tomcat without the guard), instead of a well-formed 200. Neither the guard nor Tomcat logs the message.
     */
    @Test
    void aFailureAfterTheResponseIsCommittedLeavesTheChunkedBodyUnterminated(CapturedOutput output) throws Exception {
        int before = output.getAll().length();
        RawAnswer answer = rawGet(COMMITTED_PATH);
        assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 200 ");
        assertThat(answer.bodyBytes()).isGreaterThanOrEqualTo(8 * 8192);
        assertThat(answer.chunkedBodyTerminated()).as("the truncated body must not end like a complete one").isFalse();
        Thread.sleep(1_000); // Tomcat's own handling (error page attempt on a committed response) runs after the close
        String logged = output.getAll().substring(before);
        assertThat(logged).doesNotContain("filter-leak@x.test").doesNotContain("Key (email)")
                .doesNotContain("in context with path"); // StandardWrapperValve's line that quotes the message
        assertThat(logged).contains("Request failed outside the controllers").contains("java.lang.IllegalStateException");
    }

    @Test
    void aCommittedAnswerThatDoesNotFailStillEndsNormally() throws Exception {
        // the control for the test above: the same raw read sees the closing chunk of a complete answer
        RawAnswer answer = rawGet("/ws/__sec01e-probe/complete");
        assertThat(answer.chunkedBodyTerminated()).isTrue();
    }
}
