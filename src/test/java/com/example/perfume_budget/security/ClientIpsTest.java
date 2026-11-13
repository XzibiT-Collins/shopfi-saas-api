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
        assertThat(ClientIps.rateLimitKey(req("not:an:ip:at:all:zz"))).isEqualTo("not:an:ip:at:all:zz");
        assertThat(ClientIps.rateLimitKey(req(null))).isEqualTo("unknown");
        assertThat(ClientIps.rateLimitKey(req("  "))).isEqualTo("unknown");
    }
}
