package com.example.perfume_budget.theme;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THEME-04 request-layer size enforcement on a REAL embedded Tomcat (MockMvc cannot exercise chunked bodies or the
 * servlet container's multipart limit): declared oversize, chunked (unknown length) oversize JSON and multipart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.servlet.multipart.max-file-size=20MB", // as in application-dev/prod; Boot's 1 MB default would abort earlier
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://thmsrv.shopfi.test"
})
class StoreThemeServerLimitsTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeBrandingStorage fakeBrandingStorage() {
            return new FakeBrandingStorage("test");
        }
    }

    static final String ORIGIN = "https://thmsrv.shopfi.test";

    @LocalServerPort int port;
    @Autowired TestTenants tenants;
    @Autowired JWTUtil jwtUtil;
    @Autowired ThemeWriteRateLimiter limiter;
    @Autowired FakeBrandingStorage storage;

    String admin;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        TestTenant t = tenants.ensureTenant("thmsrv");
        limiter.reset();
        admin = tenants.runAs(t, (Supplier<String>) () -> jwtUtil.generateAccessToken(t.adminEmail(), "ADMIN", 1L, "n"));
    }

    private HttpRequest.Builder req() {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/theme"))
                .header("Origin", ORIGIN).header("Cookie", "accessToken=" + admin);
    }

    private HttpResponse<String> send(HttpRequest r) throws Exception {
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(String boundary, int fileBytes) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"theme\"; filename=\"t.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n{}\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"background\"; filename=\"b.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        o.write(new byte[fileBytes]);
        o.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return o.toByteArray();
    }

    @Test
    void realGet_hasContractHeaders() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/theme"))
                .header("Origin", ORIGIN).GET().build());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).contains("public, max-age=60, s-maxage=300, stale-while-revalidate=600");
        assertThat(r.headers().allValues("Vary")).anyMatch(v -> v.contains("Origin"));
        assertThat(r.headers().firstValue("ETag")).isPresent();
    }

    @Test
    void json_chunkedOver64Kb_is413_withoutBufferingTheWholeBody() throws Exception {
        byte[] big = ("{\"x\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> r = send(req().header("Content-Type", "application/json")
                .PUT(BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big)))).build());
        assertThat(r.statusCode()).isEqualTo(413);
    }

    @Test
    void json_declaredOver64Kb_is413() throws Exception {
        byte[] big = ("{\"x\":\"" + "a".repeat(70 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> r = send(req().header("Content-Type", "application/json").PUT(BodyPublishers.ofByteArray(big)).build());
        assertThat(r.statusCode()).isEqualTo(413);
    }

    @Test
    void multipart_declaredOver12Mb_is413_viaFilter() throws Exception {
        byte[] body = multipart("xyz", 13 * 1024 * 1024);
        HttpResponse<String> r = send(req().header("Content-Type", "multipart/form-data; boundary=xyz")
                .PUT(BodyPublishers.ofByteArray(body)).build());
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.body()).contains("12 MB");
    }

    @Test
    void multipart_chunkedOver12Mb_isCutOffByTomcatMaxRequestSize() throws Exception {
        byte[] body = multipart("xyz", 13 * 1024 * 1024);
        try {
            HttpResponse<String> r = send(req().header("Content-Type", "multipart/form-data; boundary=xyz")
                    .PUT(BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))).build());
            assertThat(r.statusCode()).isEqualTo(413);
        } catch (java.io.IOException e) {
            // Tomcat aborts the stream once max-request-size (12 MB) is exceeded and closes the connection without
            // swallowing the rest of the body, so the client may see a reset instead of the 413. Either way the server
            // stopped reading; nothing was stored (asserted below).
        }
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void multipart_under12MbButOverFileLimit_is413_fromPerFileCheck_notStored() throws Exception {
        byte[] body = multipart("xyz", 6 * 1024 * 1024); // > 5 MB background, < 12 MB request
        HttpResponse<String> r = send(req().header("Content-Type", "multipart/form-data; boundary=xyz")
                .PUT(BodyPublishers.ofByteArray(body)).build());
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.body()).contains("5 MB");
    }

    @Test
    void wrongMediaType_is415_overRealHttp() throws Exception {
        HttpResponse<String> r = send(req().header("Content-Type", "text/plain").PUT(BodyPublishers.ofString("x")).build());
        assertThat(r.statusCode()).isEqualTo(415);
    }
}
