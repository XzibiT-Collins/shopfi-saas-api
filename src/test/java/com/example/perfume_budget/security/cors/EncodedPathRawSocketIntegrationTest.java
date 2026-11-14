package com.example.perfume_budget.security.cors;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOMAIN-01b S1 on a REAL Tomcat over a raw socket (MockMvc would hand the filters an already-decoded path):
 * {@code %70latform}, {@code plat%66orm} and {@code %6fnboarding} must never reach a CORS zone or a platform
 * token decision as anything but 400 without CORS headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.platform-domains=shopfi.example",
        "platform.cors.allowed-origins=https://console.example.com",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
class EncodedPathRawSocketIntegrationTest extends AbstractPostgresIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestTenants tenants;

    private record Raw(int status, String headers) {
        String header(String name) {
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":")) {
                    return line.substring(line.indexOf(':') + 1).strip();
                }
            }
            return null;
        }
    }

    private Raw send(String method, String rawPath, String origin) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            String req = method + " " + rawPath + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nOrigin: " + origin
                    + "\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            s.getInputStream().transferTo(out);
            String text = out.toString(StandardCharsets.ISO_8859_1);
            int status = Integer.parseInt(text.substring(9, 12));
            int end = text.indexOf("\r\n\r\n");
            return new Raw(status, end < 0 ? text : text.substring(0, end));
        }
    }

    @Test
    void encodedPlatformPaths_areRefusedWith400_andNeverGetCorsHeaders() throws Exception {
        tenants.acme();
        for (String path : List.of("/api/v1/%70latform/auth/login", "/api/v1/plat%66orm/auth/login",
                "/api/v1/platform/%6fnboarding/x", "/api/v1/%50LATFORM/auth/login", "/api/v1/platform/%6Fnboarding/signup")) {
            for (String origin : List.of("https://acme.shopfi.test", "https://console.example.com", "https://shopfi.example")) {
                Raw r = send("POST", path, origin);
                assertThat(r.status()).as("%s from %s", path, origin).isEqualTo(400);
                assertThat(r.header("Access-Control-Allow-Origin")).as("ACAO %s %s", path, origin).isNull();
                assertThat(r.header("Access-Control-Allow-Credentials")).as("ACAC %s %s", path, origin).isNull();
            }
        }
    }

    @Test
    void plainPathsStillWork_andTenantOriginStaysOutOfThePlatformZone() throws Exception {
        tenants.acme();
        Raw tenantOnPlatform = send("POST", "/api/v1/platform/auth/login", "https://acme.shopfi.test");
        assertThat(tenantOnPlatform.status()).isEqualTo(403);
        assertThat(tenantOnPlatform.header("Access-Control-Allow-Origin")).isNull();
        Raw console = send("GET", "/api/v1/platform/auth/me", "https://console.example.com");
        assertThat(console.status()).isEqualTo(401);
        assertThat(console.header("Access-Control-Allow-Origin")).isEqualTo("https://console.example.com");
        assertThat(console.header("Access-Control-Allow-Credentials")).isEqualTo("true");
        Raw tenantPath = send("GET", "/api/v1/category/all", "https://acme.shopfi.test");
        assertThat(tenantPath.status()).isEqualTo(200);
        assertThat(tenantPath.header("Access-Control-Allow-Origin")).isEqualTo("https://acme.shopfi.test");
        Raw encodedTenantPath = send("GET", "/api/v1/categor%79/all", "https://acme.shopfi.test");
        assertThat(encodedTenantPath.status()).as("an encoded NON-platform path is not guarded").isEqualTo(200);
    }
}
