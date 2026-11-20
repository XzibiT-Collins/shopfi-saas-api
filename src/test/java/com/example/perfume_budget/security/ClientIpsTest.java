package com.example.perfume_budget.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** PROV-02b AC1: the client key comes only from the container-resolved address; IPv6 is bucketed by /64. */
class ClientIpsTest {

    private static MockHttpServletRequest req(String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        return r;
    }

    @Test
    void forwardingHeadersAreNeverConsultedByTheResolver() {
        MockHttpServletRequest r = req("203.0.113.9");
        r.addHeader("X-Forwarded-For", "6.6.6.6, 7.7.7.7");
        r.addHeader("X-Real-IP", "8.8.8.8");
        r.addHeader("Forwarded", "for=9.9.9.9;proto=https");
        r.addHeader("X-Client-IP", "5.5.5.5");
        r.addHeader("True-Client-IP", "4.4.4.4");
        assertThat(ClientIps.clientIp(r)).isEqualTo("203.0.113.9");
        assertThat(ClientIps.rateLimitKey(r)).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv6AddressesInTheSame64ShareOneBucket_otherPrefixesDoNot() {
        String a = ClientIps.rateLimitKey(req("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
        String b = ClientIps.rateLimitKey(req("2001:DB8:1:2::1"));
        String c = ClientIps.rateLimitKey(req("2001:db8:1:3::1"));
        assertThat(a).isEqualTo(b).isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(c).isNotEqualTo(a);
    }

    @Test
    void ipv6EdgeForms_zoneIdMappedV4_andGarbageNeverThrowOrResolveDns() {
        assertThat(ClientIps.rateLimitKey(req("fe80::1%eth0"))).isEqualTo("fe80:0000:0000:0000::/64");
        assertThat(ClientIps.rateLimitKey(req("::ffff:192.0.2.5"))).isEqualTo("192.0.2.5");
        assertThat(ClientIps.rateLimitKey(req("::1"))).isEqualTo("0000:0000:0000:0000::/64");
        assertThat(ClientIps.rateLimitKey(req("not:an:ip:at:all:zz"))).isEqualTo("invalid");
        assertThat(ClientIps.rateLimitKey(req(null))).isEqualTo("unknown");
        assertThat(ClientIps.rateLimitKey(req("  "))).isEqualTo("unknown");
    }

    /** PROV-05 AC2: ports, brackets, garbage and markup in the container-resolved address never become a key / stored value. */
    @Test
    void onlyIpLiteralsSurviveAsTheClientAddress() {
        for (String bad : new String[]{"1.2.3.4:8080", "[::1]", "[2001:db8::1]:443", "<script>alert(1)</script>", "evil.example.com",
                "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4x", "1.2.3.4, 5.6.7.8", "::1%", "fe80::1%bad zone", "a".repeat(5000),
                "12345::1", "2001:db8::1::2"}) {
            assertThat(ClientIps.clientIp(req(bad))).as(bad).isEqualTo("invalid");
            assertThat(ClientIps.rateLimitKey(req(bad))).as(bad).isEqualTo("invalid");
        }
        for (String good : new String[]{"203.0.113.9", "0.0.0.0", "255.255.255.255", "2001:db8::1", "::1", "fe80::1%eth0", "::ffff:192.0.2.5"}) {
            assertThat(ClientIps.clientIp(req(good))).as(good).isEqualTo(good);
        }
    }
}
