package com.example.perfume_budget.theme;

import com.example.perfume_budget.config.MultipartTempDirInitializer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THEME-04c on a REAL Tomcat (random port, raw sockets): malformed/truncated multipart answer the contract shapes
 * (not 500), the rate limit runs before multipart spooling (a 429 creates no temp file and does not read the body),
 * and the size filter cannot be bypassed with a percent-encoded path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.servlet.multipart.max-file-size=20MB",
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://thmmp.shopfi.test",
        "theme.put-rate-limit.per-minute=3",
        "server.tomcat.connection-timeout=2s",   // read timeout: a body shorter than its Content-Length must not pin a thread
        "server.tomcat.threads.max=3",
        "server.tomcat.threads.min-spare=1"
})
class StoreThemeMultipartHttpTest extends AbstractPostgresIntegrationTest {

    static final Path SPOOL;

    static {
        try {
            SPOOL = Files.createTempDirectory("t04c-spool-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void spool(DynamicPropertyRegistry r) {
        r.add("spring.servlet.multipart.location", SPOOL::toString);
    }

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeBrandingStorage fakeBrandingStorage() {
            return new FakeBrandingStorage("test");
        }
    }

    static final String HOST = "thmmp.shopfi.test";
    static final String ORIGIN = "https://" + HOST;

    @LocalServerPort int port;
    @Autowired TestTenants tenants;
    @Autowired JWTUtil jwtUtil;
    @Autowired ThemeWriteRateLimiter limiter;

    String admin;
    String adminEmail;

    @BeforeEach
    void setUp() throws Exception {
        TestTenant t = tenants.ensureTenant("thmmp");
        limiter.reset();
        adminEmail = t.adminEmail();
        admin = tenants.runAs(t, (Supplier<String>) () -> jwtUtil.generateAccessToken(t.adminEmail(), "ADMIN", 1L, "n"));
        clearSpool();
    }

    // ------------------------------------------------------------------------------------------ helpers

    record Resp(int status, String headers, String body) {}

    /** The JVM spools into SPOOL/instance-<pid>-<uuid> (THEME-04d); the marker file is not a spooled part. */
    private static void clearSpool() throws IOException {
        for (Path p : spooled()) {
            Files.deleteIfExists(p);
        }
    }

    private static List<Path> spooled() throws IOException {
        try (Stream<Path> s = Files.walk(SPOOL)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(MultipartTempDirInitializer.MARKER))
                    .toList();
        }
    }

    private String head(String method, String path, String contentType, long contentLength, boolean auth) {
        return method + " " + path + " HTTP/1.1\r\nHost: " + HOST + "\r\nOrigin: " + ORIGIN + "\r\n"
                + (auth ? "Cookie: accessToken=" + admin + "\r\n" : "")
                + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n")
                + (contentLength >= 0 ? "Content-Length: " + contentLength + "\r\n" : "")
                + "Connection: close\r\n\r\n";
    }

    private Socket open(int soTimeoutMs) throws IOException {
        Socket s = new Socket("localhost", port);
        s.setSoTimeout(soTimeoutMs);
        return s;
    }

    private static Resp readResponse(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                all.write(buf, 0, n);
                if (complete(all.toString(StandardCharsets.UTF_8))) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            if (all.size() == 0) {
                throw e;
            }
        }
        String raw = all.toString(StandardCharsets.UTF_8);
        int split = raw.indexOf("\r\n\r\n");
        String head = split < 0 ? raw : raw.substring(0, split);
        String body = split < 0 ? "" : raw.substring(split + 4);
        return new Resp(Integer.parseInt(head.substring(9, 12)), head, body);
    }

    /** True once the status line, headers and a full Content-Length body (or a chunked end) have arrived. */
    private static boolean complete(String raw) {
        int split = raw.indexOf("\r\n\r\n");
        if (split < 0) {
            return false;
        }
        String h = raw.substring(0, split).toLowerCase();
        int i = h.indexOf("content-length:");
        if (i < 0) {
            return raw.endsWith("0\r\n\r\n") || h.contains("transfer-encoding: chunked") && raw.endsWith("0\r\n\r\n");
        }
        int len = Integer.parseInt(h.substring(i + 15, h.indexOf("\r\n", i) < 0 ? h.length() : h.indexOf("\r\n", i)).trim());
        return raw.length() - split - 4 >= len;
    }

    private Resp exchange(String head, byte[] body) throws Exception {
        try (Socket s = open(10_000)) {
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return readResponse(s);
        }
    }

    private static byte[] validMultipartStart(String b) {
        return ("--" + b + "\r\nContent-Disposition: form-data; name=\"theme\"; filename=\"t.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n{}\r\n--" + b
                + "\r\nContent-Disposition: form-data; name=\"background\"; filename=\"b.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final String SHAPE_400 = "\"path\":\"$\"";

    // ------------------------------------------------------------------------------------------ AC2

    @Test
    void multipartWithoutBoundary_is400_invalidFormat_not500() throws Exception {
        Resp r = exchange(head("PUT", "/api/v1/theme", "multipart/form-data", 5, true), "hello".getBytes());
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.body()).contains("INVALID_FORMAT").contains(SHAPE_400).doesNotContain("Unexpected error");
        assertThat(r.headers().toLowerCase()).contains("cache-control: no-store");
        assertThat(spooled()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ THEME-04d AC1: boundary

    private void assertBoundaryRejected(String contentType) throws Exception {
        limiter.reset(); // the test profile allows only 3 writes/min
        byte[] body = validMultipartStart("xyz");
        Resp r = exchange(head("PUT", "/api/v1/theme", contentType, body.length, true), body);
        assertThat(r.status()).as(contentType + " -> " + r.body()).isEqualTo(400);
        assertThat(r.body()).contains("INVALID_FORMAT").contains(SHAPE_400).doesNotContain("Unexpected error");
        assertThat(r.headers().toLowerCase()).contains("cache-control: no-store");
        assertThat(spooled()).isEmpty();
    }

    @Test
    void emptyBoundary_is400_notTheGeneric500() throws Exception {
        assertBoundaryRejected("multipart/form-data; boundary=");
    }

    @Test
    void quotedEmptyBoundary_is400() throws Exception {
        assertBoundaryRejected("multipart/form-data; boundary=\"\"");
    }

    @Test
    void whitespaceBoundary_is400() throws Exception {
        assertBoundaryRejected("multipart/form-data; boundary=   ");
        assertBoundaryRejected("multipart/form-data; boundary=\"   \"");
    }

    @Test
    void overLongBoundary_is400() throws Exception {
        assertBoundaryRejected("multipart/form-data; boundary=" + "a".repeat(71));
    }

    @Test
    void boundaryWithIllegalCharacters_is400() throws Exception {
        assertBoundaryRejected("multipart/form-data; boundary=ab@cd");
        assertBoundaryRejected("multipart/form-data; boundary=ab cd");   // unquoted space
        assertBoundaryRejected("multipart/form-data; boundary=\"abc \""); // trailing space
        assertBoundaryRejected("multipart/form-data; boundary=a<b>");
        assertBoundaryRejected("multipart/form-data; boundary=a,b");     // Tomcat would split at the comma
    }

    @Test
    void duplicateBoundaryParameters_are400() throws Exception {
        assertBoundaryRejected("multipart/form-data; boundary=xyz; boundary=abc");
        assertBoundaryRejected("multipart/form-data; boundary=xyz; BOUNDARY=xyz");
    }

    @Test
    void uppercaseBoundaryName_emptyIs400_validIsAccepted() throws Exception {
        assertBoundaryRejected("multipart/form-data; BOUNDARY=");
        assertBoundaryRejected("multipart/form-data; Boundary=\"\"");
        byte[] body = validBody("xyz");
        Resp ok = exchange(head("PUT", "/api/v1/theme", "multipart/form-data; BOUNDARY=xyz", body.length, true), body);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
    }

    @Test
    void quotedValidBoundary_stillWorks() throws Exception {
        byte[] body = validBody("a b+c");
        Resp ok = exchange(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=\"a b+c\"", body.length, true), body);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
    }

    @Test
    void maximumLengthBoundary_70chars_isAccepted() throws Exception {
        String b = "b".repeat(70);
        byte[] body = validBody(b);
        Resp ok = exchange(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=" + b, body.length, true), body);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
    }

    @Test
    void normalValidUpload_is200() throws Exception {
        byte[] body = validBody("xyz");
        Resp ok = exchange(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=xyz", body.length, true), body);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(spooled()).isEmpty();
    }

    @Test
    void resolverMultipartException_onThemePath_isMappedTo400_byTheAdviceSafetyNet() throws Exception {
        // Direct unit check of the safety net (the guard normally answers first).
        var handler = new com.example.perfume_budget.exception.handlers.GlobalExceptionHandler();
        var theme = new org.springframework.mock.web.MockHttpServletRequest("PUT", "/api/v1/theme");
        theme.setServletPath("/api/v1/theme");
        var other = new org.springframework.mock.web.MockHttpServletRequest("PUT", "/api/v1/products");
        other.setServletPath("/api/v1/products");
        var ex = new org.springframework.web.multipart.MultipartException("Failed to parse multipart servlet request");
        var r1 = handler.handleMultipartException(ex, theme);
        assertThat(r1.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(r1.getBody())).contains("INVALID_FORMAT");
        assertThat(handler.handleMultipartException(ex, other).getStatusCode().value()).isEqualTo(500); // unchanged elsewhere
    }

    private static byte[] validBody(String b) {
        String t = ThemeTestDocs.defaults().toString();
        return ("--" + b + "\r\nContent-Disposition: form-data; name=\"theme\"; filename=\"t.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + t + "\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void malformedMultipartBody_withBoundary_is400() throws Exception {
        byte[] garbage = "this is not multipart at all, no delimiter anywhere".getBytes();
        Resp r = exchange(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=xyz", garbage.length, true), garbage);
        // Tomcat finds no part at all in a delimiter-less body; the controller then answers the contract 400 (not 500).
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":").doesNotContain("Unexpected error");
        assertThat(spooled()).isEmpty();
    }

    @Test
    void multipartCutOffBeforeClosingBoundary_withMatchingLength_is400() throws Exception {
        byte[] body = validMultipartStart("xyz"); // file part never closed
        Resp r = exchange(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=xyz", body.length, true), body);
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.body()).contains("INVALID_FORMAT");
        assertThat(spooled()).isEmpty();
    }

    @Test
    void bodyShorterThanContentLength_isAnsweredAfterTheReadTimeout_andPinsNoThread() throws Exception {
        // Tomcat is limited to 3 worker threads and a 2 s read timeout. Three stalled bodies must each be released
        // (400), after which the server still answers.
        List<CompletableFuture<Resp>> stalled = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            stalled.add(CompletableFuture.supplyAsync(() -> {
                try (Socket s = open(15_000)) {
                    byte[] part = validMultipartStart("xyz");
                    s.getOutputStream().write(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=xyz", 100_000, true)
                            .getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().write(part);
                    s.getOutputStream().flush();
                    return readResponse(s); // never send the rest
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (CompletableFuture<Resp> f : stalled) {
            Resp r = f.get();
            assertThat(r.status()).as(r.headers() + r.body()).isEqualTo(400);
            assertThat(r.body()).contains("INVALID_FORMAT");
        }
        assertThat((System.nanoTime() - start) / 1_000_000_000d).isLessThan(12d);
        Resp get = exchange(head("GET", "/api/v1/theme", null, -1, false), null);
        assertThat(get.status()).isEqualTo(200);
        assertThat(spooled()).isEmpty();
    }

    @Test
    void multipartOver12Mb_isThe413ThemeShape_withTheRealLimit_notTenMb() throws Exception {
        // chunked: no declared length, so the size filter cannot answer; Tomcat's max-request-size aborts the parse
        try (Socket s = open(10_000)) {
            OutputStream out = s.getOutputStream();
            out.write(("PUT /api/v1/theme HTTP/1.1\r\nHost: " + HOST + "\r\nOrigin: " + ORIGIN + "\r\nCookie: accessToken=" + admin
                    + "\r\nContent-Type: multipart/form-data; boundary=xyz\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            byte[] start = validMultipartStart("xyz");
            byte[] block = new byte[64 * 1024];
            writeChunk(out, start);
            Resp r = null;
            try {
                for (int i = 0; i < 13 * 16; i++) {
                    writeChunk(out, block);
                }
                out.flush();
            } catch (IOException e) {
                // server already answered and closed; read the answer below
            }
            r = readResponse(s);
            assertThat(r.status()).isEqualTo(413);
            assertThat(r.body()).contains("12 MB").doesNotContain("10MB");
        }
        assertThat(spooled()).isEmpty();
    }

    private static void writeChunk(OutputStream out, byte[] data) throws IOException {
        out.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------ AC3

    @Test
    void controlSpoolDirIsReallyUsed_byAnAuthorisedStalledUpload() throws Exception {
        // Guards the observation used below: while an allowed multipart PUT is being read, a part file is on disk.
        try (Socket s = open(10_000)) {
            byte[] start = validMultipartStart("xyz");
            byte[] chunk = new byte[256 * 1024];
            s.getOutputStream().write(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=xyz", 5_000_000, true)
                    .getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().write(start);
            s.getOutputStream().write(chunk);
            s.getOutputStream().flush();
            long deadline = System.currentTimeMillis() + 1500;
            while (spooled().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(spooled()).as("part spooled in the dedicated multipart location").isNotEmpty();
            assertThat(spooled().get(0).getParent().getFileName().toString())
                    .as("each JVM spools into its own instance-<pid>-<uuid> subdirectory").startsWith("instance-");
        }
    }

    @Test
    void throttledRequest_isAnswered429_withoutReadingOrSpoolingTheBody() throws Exception {
        String key = "thmmp:" + adminEmail;
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(key)).isZero();
        }
        try (Socket s = open(3_000)) {
            byte[] start = validMultipartStart("xyz");
            s.getOutputStream().write(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=xyz", 5_000_000, true)
                    .getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().write(start);
            s.getOutputStream().write(new byte[256 * 1024]); // part of a large file, then the client stalls
            s.getOutputStream().flush();
            Resp r = readResponse(s); // must arrive while the body is still incomplete (3 s socket timeout < Tomcat's 2 s only if parsed)
            assertThat(r.status()).as(r.body()).isEqualTo(429);
            assertThat(r.headers().toLowerCase()).contains("retry-after:").contains("cache-control: no-store");
            assertThat(spooled()).as("a throttled request creates no temp file").isEmpty();
        }
        assertThat(spooled()).isEmpty();
    }

    @Test
    void throttledRequest_429_comesBeforeMalformedMultipart_400() throws Exception {
        String key = "thmmp:" + adminEmail;
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(key);
        }
        Resp r = exchange(head("PUT", "/api/v1/theme", "multipart/form-data", 5, true), "hello".getBytes());
        assertThat(r.status()).isEqualTo(429);
    }

    @Test
    void unauthenticatedRequest_neverChargesTheBucket_andIsNotParsed() throws Exception {
        byte[] body = validMultipartStart("xyz");
        Resp r = exchange(head("PUT", "/api/v1/theme", "multipart/form-data; boundary=xyz", body.length, false), body);
        assertThat(r.status()).isEqualTo(401);
        assertThat(limiter.size()).isZero();
        assertThat(spooled()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ AC4

    @Test
    void percentEncodedThemePath_cannotBypassTheSizeFilter() throws Exception {
        // No cookie: the size filter runs BEFORE authentication, so a recognised path gets 413; if the path were not
        // recognised the request would reach authentication and get 401.
        byte[] big = ("{\"x\":\"" + "a".repeat(70 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        Resp encoded = exchange(head("PUT", "/api/v1/%74heme", "application/json", big.length, false), big);
        Resp plain = exchange(head("PUT", "/api/v1/theme", "application/json", big.length, false), big);
        assertThat(plain.status()).isEqualTo(413);
        assertThat(encoded.status()).as(encoded.body()).isEqualTo(413);
    }

    // ------------------------------------------------------------------------------------------ AC1 over real HTTP

    @Test
    void realGet_refererOnly_isNotSharedCacheable_originPresent_is() throws Exception {
        String refererOnly = "GET /api/v1/theme HTTP/1.1\r\nHost: " + HOST + "\r\nReferer: " + ORIGIN + "/p\r\nConnection: close\r\n\r\n";
        Resp r = exchange(refererOnly, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().toLowerCase()).contains("cache-control: private, no-store").doesNotContain("s-maxage");
        assertThat(r.headers().toLowerCase()).contains("vary: referer"); // our "Origin, Referer" merged with CORS's Origin line
        Resp o = exchange(head("GET", "/api/v1/theme", null, -1, false), null);
        assertThat(o.headers().toLowerCase()).contains("s-maxage=300");
    }

    // ------------------------------------------------------------------------------------------ THEME-04d AC3: Origin

    /**
     * MAIN-RED-01: the layer that answers a duplicated Origin is DOMAIN-01's CORS filter (it runs before the theme
     * controller), so the status is 403 "Invalid CORS request", not the controller's 400. Whoever answers, the
     * response must carry no tenant data, no CORS grant and must not be cacheable by a shared cache.
     */
    @Test
    void getWithTwoOriginHeaders_isRejectedByCors403_noStore_noCorsGrant_notCacheable() throws Exception {
        String twice = "GET /api/v1/theme HTTP/1.1\r\nHost: " + HOST + "\r\nOrigin: " + ORIGIN
                + "\r\nOrigin: https://other.shopfi.test\r\nConnection: close\r\n\r\n";
        Resp r = exchange(twice, null);
        String h = r.headers().toLowerCase();
        assertThat(r.status()).as(r.headers() + r.body()).isEqualTo(403);
        assertThat(r.body()).contains("Invalid CORS request").doesNotContain("\"tokens\"");
        assertThat(h).contains("cache-control: no-cache, no-store, max-age=0, must-revalidate").contains("vary: origin")
                .doesNotContain("s-maxage").doesNotContain("max-age=60")
                .doesNotContain("public").doesNotContain("etag:");
        assertThat(h).doesNotContain("access-control-allow-origin").doesNotContain("access-control-allow-credentials");
    }

    @Test
    void getWithTwoOriginHeaders_orderSwapped_isAlsoRejected_andNoSingleOriginLeaksThroughEither() throws Exception {
        String twice = "GET /api/v1/theme HTTP/1.1\r\nHost: " + HOST + "\r\nOrigin: https://other.shopfi.test"
                + "\r\nOrigin: " + ORIGIN + "\r\nConnection: close\r\n\r\n";
        Resp r = exchange(twice, null);
        assertThat(r.status()).as(r.headers() + r.body()).isEqualTo(403);
        assertThat(r.headers().toLowerCase()).contains("cache-control: no-cache, no-store, max-age=0, must-revalidate")
                .doesNotContain("access-control-allow-origin")
                .doesNotContain("s-maxage");
        assertThat(r.body()).doesNotContain("\"tokens\"");
    }

    @Test
    void getWithAFoldedOriginList_isNeverServedAsAPublicTheme() throws Exception {
        String folded = "GET /api/v1/theme HTTP/1.1\r\nHost: " + HOST + "\r\nOrigin: " + ORIGIN
                + ", https://other.shopfi.test\r\nConnection: close\r\n\r\n";
        Resp r = exchange(folded, null);
        assertThat(r.status()).as(r.body()).isIn(400, 403);
        assertThat(r.headers().toLowerCase()).doesNotContain("s-maxage");
    }

    @Test
    void getWithOneOrigin_isStill200() throws Exception {
        Resp r = exchange(head("GET", "/api/v1/theme", null, -1, false), null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        // positive counterpart of the duplicate-Origin rejection: one valid Origin is granted CORS and is cacheable
        assertThat(r.headers().toLowerCase()).contains("access-control-allow-origin: " + ORIGIN).contains("s-maxage=300");
        assertThat(r.body()).contains("\"tokens\"");
    }
}
