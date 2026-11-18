package com.example.perfume_budget.platform.domain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOMAIN-02 AC2: the production JNDI resolver against a tiny local UDP DNS server (no internet): TXT answers are parsed
 * (multi-string records joined), NXDOMAIN is a definitive empty answer, and a server that never answers fails within
 * the configured timeout instead of hanging.
 */
class JndiDnsTxtResolverTest {

    private DatagramSocket server;
    private Thread serverThread;
    private final AtomicInteger queries = new AtomicInteger();

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    /** Starts a UDP server; {@code responder} turns a raw query into a raw answer (null = never answer). */
    private String start(Function<byte[], byte[]> responder) throws Exception {
        server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        serverThread = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!server.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    server.receive(p);
                    queries.incrementAndGet();
                    byte[] q = java.util.Arrays.copyOf(p.getData(), p.getLength());
                    byte[] a = responder.apply(q);
                    if (a != null) {
                        server.send(new DatagramPacket(a, a.length, p.getSocketAddress()));
                    }
                } catch (Exception e) {
                    return;
                }
            }
        }, "fake-dns");
        serverThread.setDaemon(true);
        serverThread.start();
        return "127.0.0.1:" + server.getLocalPort();
    }

    /** End of the question section (header 12 bytes + QNAME + QTYPE + QCLASS). */
    private static int questionEnd(byte[] q) {
        int i = 12;
        while (q[i] != 0) {
            i += (q[i] & 0xff) + 1;
        }
        return i + 1 + 4;
    }

    private static byte[] answer(byte[] q, int rcode, List<List<String>> txtRecords) {
        int qEnd = questionEnd(q);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(q[0]);
        out.write(q[1]);
        out.write(0x81);
        out.write(0x80 | rcode);
        out.write(0); out.write(1);                        // QDCOUNT
        out.write(0); out.write(txtRecords.size());        // ANCOUNT
        out.write(0); out.write(0); out.write(0); out.write(0);
        out.write(q, 12, qEnd - 12);
        for (List<String> rec : txtRecords) {
            ByteArrayOutputStream rdata = new ByteArrayOutputStream();
            for (String s : rec) {
                byte[] b = s.getBytes(StandardCharsets.US_ASCII);
                rdata.write(b.length);
                rdata.write(b, 0, b.length);
            }
            out.write(0xC0); out.write(0x0C);              // name: pointer to the question
            out.write(0); out.write(16);                   // TYPE TXT
            out.write(0); out.write(1);                    // CLASS IN
            out.write(0); out.write(0); out.write(0); out.write(60);
            out.write(rdata.size() >> 8); out.write(rdata.size() & 0xff);
            out.write(rdata.toByteArray(), 0, rdata.size());
        }
        return out.toByteArray();
    }

    @Test
    void txtRecordsAreReturned_multiStringRecordsJoined_quotesRemoved() throws Exception {
        String dns = start(q -> answer(q, 0, List.of(
                List.of("shopfi-verification=abc123"),
                List.of("v=spf1 include:mail.example", " -all"),
                List.of("shopfi-", "verification=split"))));
        List<String> txt = new JndiDnsTxtResolver(List.of(dns), 500, 1).lookupTxt("_shopfi-verification.shop.acme-brand.com");
        assertThat(txt).containsExactlyInAnyOrder("shopfi-verification=abc123", "v=spf1 include:mail.example -all",
                "shopfi-verification=split");
    }

    @Test
    void nxdomainIsADefinitiveEmptyAnswer() throws Exception {
        String dns = start(q -> answer(q, 3, List.of()));
        assertThat(new JndiDnsTxtResolver(List.of(dns), 500, 1).lookupTxt("_shopfi-verification.nope.acme-brand.com")).isEmpty();
    }

    @Test
    void noTxtRecordsIsAnEmptyAnswer() throws Exception {
        String dns = start(q -> answer(q, 0, List.of()));
        assertThat(new JndiDnsTxtResolver(List.of(dns), 500, 1).lookupTxt("_shopfi-verification.empty.acme-brand.com")).isEmpty();
    }

    @Test
    void serverFailureIsNotTreatedAsAbsent() throws Exception {
        String dns = start(q -> answer(q, 2, List.of())); // SERVFAIL
        assertThatThrownBy(() -> new JndiDnsTxtResolver(List.of(dns), 300, 0).lookupTxt("_x.shop.acme-brand.com"))
                .isInstanceOf(DnsTxtResolver.DnsLookupException.class);
    }

    @Test
    void aSilentServerTimesOutWithinTheConfiguredBound() throws Exception {
        String dns = start(q -> null);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> new JndiDnsTxtResolver(List.of(dns), 200, 1).lookupTxt("_x.shop.acme-brand.com"))
                .isInstanceOf(DnsTxtResolver.DnsLookupException.class)
                .hasMessageNotContaining("acme-brand");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // 200 ms + 400 ms (one retry, doubled) = 600 ms; generous upper bound for a loaded CI box
        assertThat(ms).isBetween(150L, 5000L);
        assertThat(queries.get()).isBetween(1, 2);
    }

    @Test
    void urlShapedOrMalformedNamesAreRefusedBeforeJndiSeesThem() throws Exception {
        String dns = start(q -> answer(q, 0, List.of(List.of("x"))));
        JndiDnsTxtResolver r = new JndiDnsTxtResolver(List.of(dns), 300, 1);
        for (String bad : java.util.Arrays.asList(null, "", "ldap://127.0.0.1:1389/x", "dns://" + dns + "/example.com",
                "rmi://127.0.0.1/x", "a/b.example.com", "a b.example.com", "user@example.com", "example.com:53",
                "a..b.com", ".a.com", "a.com.", "-a.com", "a-.com", "a".repeat(64) + ".com", "x\u0000.com", "b\u00fccher.de")) {
            assertThatThrownBy(() -> r.lookupTxt(bad)).as(String.valueOf(bad)).isInstanceOf(DnsTxtResolver.DnsLookupException.class);
        }
        assertThat(queries.get()).as("nothing was sent").isZero();
        assertThat(JndiDnsTxtResolver.isPlainName("_shopfi-verification.shop.acme-brand.com")).isTrue();
        assertThat(JndiDnsTxtResolver.isPlainName("xn--bcher-kva.de")).isTrue();
        assertThat(r.lookupTxt("_shopfi-verification.shop.acme-brand.com")).containsExactly("x");
    }

    @Test
    void zeroRetriesStillSendsOneQuery_neverAnAutomaticFailure() throws Exception {
        String dns = start(q -> answer(q, 0, List.of(List.of("shopfi-verification=one"))));
        assertThat(new JndiDnsTxtResolver(List.of(dns), 500, 0).lookupTxt("_x.shop.acme-brand.com"))
                .containsExactly("shopfi-verification=one");
        assertThat(queries.get()).isEqualTo(1);
    }

    @Test
    void unquote_handlesJndiRenderings() {
        assertThat(JndiDnsTxtResolver.unquote("plain")).isEqualTo("plain");
        assertThat(JndiDnsTxtResolver.unquote("\"a b\" \"c\"")).isEqualTo("a bc");
        assertThat(JndiDnsTxtResolver.unquote("\"x\\\"y\"")).isEqualTo("x\"y");
        assertThat(JndiDnsTxtResolver.unquote("  spaced  ")).isEqualTo("spaced");
    }
}
