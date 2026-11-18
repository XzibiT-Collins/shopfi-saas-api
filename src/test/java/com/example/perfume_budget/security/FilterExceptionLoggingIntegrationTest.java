package com.example.perfume_budget.security;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
                chain.doFilter(req, res);
            };
            FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(filter);
            bean.addUrlPatterns(PATH + "*");
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return bean;
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
        // Unchanged by the guard (also the answer without it): the container's /error dispatch is anonymous, so
        // Spring Security's entry point answers 401 there. Changing that is a separate decision (see the PR).
        assertThat(response.statusCode()).isEqualTo(401);
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
}
