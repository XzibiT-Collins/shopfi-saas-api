package com.example.perfume_budget.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROV-02b AC1 against a REAL embedded Tomcat that loads the SHIPPED application.properties plus the dev and prod
 * profile files, so a regression to {@code server.forward-headers-strategy=framework} (or a profile override) is
 * caught. The test client connects from 127.0.0.1, which the default internal-proxies setting treats as our own
 * trusted proxy, i.e. it plays "the reverse proxy"; anything it appends/forwards is what a proxy would. Entries to
 * the left of the right-most untrusted hop model client-supplied spoofing and must never be chosen.
 */
class ProxyTrustTest {

    @Configuration
    @Import({ServletWebServerFactoryAutoConfiguration.class, EmbeddedWebServerFactoryCustomizerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, ProbeController.class})
    static class App {
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe(HttpServletRequest r) {
            return ClientIps.clientIp(r) + "|" + ClientIps.rateLimitKey(r);
        }
    }

    private static ConfigurableApplicationContext boot(String profile, String... extraArgs) {
        // command-line args beat the profile files (which set server.port from env)
        String[] args = new String[extraArgs.length + 1];
        args[0] = "--server.port=0";
        System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
        return new SpringApplicationBuilder(App.class).profiles(profile)
                .properties("spring.main.banner-mode=off", "ACTIVE_PROFILE=" + profile).run(args);
    }

    /** Sends the given header lines (name, value, name, value...) and returns "clientIp|bucketKey". */
    private static String probe(ConfigurableApplicationContext ctx, String... headers) throws Exception {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/probe"));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    @Test
    void bothProfilesUseNativeForwarding_andNeverRegisterSpringsLeftmostForwardedHeaderFilter() {
        for (String profile : new String[]{"dev", "prod"}) {
            try (ConfigurableApplicationContext ctx = boot(profile)) {
                assertThat(ctx.getEnvironment().getProperty("server.forward-headers-strategy"))
                        .as(profile).isEqualTo("native");
                assertThat(ctx.getEnvironment().getProperty("server.tomcat.remoteip.remote-ip-header"))
                        .as(profile).isEqualTo("X-Forwarded-For");
                assertThat(ctx.getBeanNamesForType(ForwardedHeaderFilter.class)).as(profile).isEmpty();
            }
        }
    }

    @Test
    void spoofedHeadersNeverChooseTheBucket_inEitherProfile() throws Exception {
        for (String profile : new String[]{"dev", "prod"}) {
            try (ConfigurableApplicationContext ctx = boot(profile)) {
                // client sent "6.6.6.6", our proxy appended the real peer 7.7.7.7: right-most untrusted wins
                assertThat(probe(ctx, "X-Forwarded-For", "6.6.6.6, 7.7.7.7")).as(profile).isEqualTo("7.7.7.7|7.7.7.7");
                // many spoofed hops on the left
                assertThat(probe(ctx, "X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3, 6.6.6.6, 7.7.7.7"))
                        .as(profile).startsWith("7.7.7.7|");
                // a trusted internal hop after the real client is skipped, still not the left-most
                assertThat(probe(ctx, "X-Forwarded-For", "6.6.6.6, 7.7.7.7, 10.1.2.3")).as(profile).startsWith("7.7.7.7|");
                // split across two header lines: lines are joined in order, proxy line last
                assertThat(probe(ctx, "X-Forwarded-For", "6.6.6.6", "X-Forwarded-For", "7.7.7.7"))
                        .as(profile).startsWith("7.7.7.7|");
                // not a trusted-proxy hop value: spoof "internal" IPs on the left cannot win either
                assertThat(probe(ctx, "X-Forwarded-For", "10.9.9.9, 7.7.7.7")).as(profile).startsWith("7.7.7.7|");
            }
        }
    }

    @Test
    void xRealIpAndForwardedHeadersAreIgnored() throws Exception {
        try (ConfigurableApplicationContext ctx = boot("prod")) {
            assertThat(probe(ctx, "X-Real-IP", "6.6.6.6")).isEqualTo("127.0.0.1|127.0.0.1");
            assertThat(probe(ctx, "Forwarded", "for=6.6.6.6;proto=https")).isEqualTo("127.0.0.1|127.0.0.1");
            assertThat(probe(ctx, "X-Client-IP", "6.6.6.6", "True-Client-IP", "5.5.5.5")).isEqualTo("127.0.0.1|127.0.0.1");
            // with a real proxy-appended XFF present, the other headers still do not matter
            assertThat(probe(ctx, "X-Forwarded-For", "7.7.7.7", "X-Real-IP", "6.6.6.6", "Forwarded", "for=5.5.5.5"))
                    .isEqualTo("7.7.7.7|7.7.7.7");
        }
    }

    @Test
    void ipv6ClientsAreBucketedBySlash64_notByFullAddress() throws Exception {
        try (ConfigurableApplicationContext ctx = boot("prod")) {
            String one = probe(ctx, "X-Forwarded-For", "6.6.6.6, 2001:db8:aaaa:bbbb:1:2:3:4");
            String two = probe(ctx, "X-Forwarded-For", "6.6.6.6, 2001:db8:aaaa:bbbb:ffff:eeee:dddd:cccc");
            assertThat(one.split("\\|")[1]).isEqualTo("2001:0db8:aaaa:bbbb::/64").isEqualTo(two.split("\\|")[1]);
            assertThat(one.split("\\|")[0]).isNotEqualTo(two.split("\\|")[0]);
        }
    }

    @Test
    void headersFromAnUntrustedDirectPeerAreIgnoredEntirely() throws Exception {
        // trusted set no longer contains the loopback peer: nothing in X-Forwarded-For is believed
        try (ConfigurableApplicationContext ctx = boot("prod", "--TRUSTED_PROXY_REGEX=192\\.0\\.2\\.\\d+")) {
            assertThat(probe(ctx, "X-Forwarded-For", "6.6.6.6, 7.7.7.7")).isEqualTo("127.0.0.1|127.0.0.1");
        }
    }
}
