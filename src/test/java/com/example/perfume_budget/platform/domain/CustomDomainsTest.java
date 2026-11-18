package com.example.perfume_budget.platform.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.IDN;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DOMAIN-02 AC1: one test (group) per validation rule of {@link CustomDomains}. */
class CustomDomainsTest {

    /** Realistic production-like policy: base domain, platform/api hosts, static CORS hosts, one extra reserved suffix. */
    static final CustomDomains.Policy POLICY = CustomDomains.Policy.of("shopfi.app",
            List.of("shopfi.app", "api.shopfi.app", "localhost", "127.0.0.1"),
            List.of("console.shopfi-admin.io", "perfume-budget-gs-fe.vercel.app"),
            List.of("corp.acme-internal.com"));

    /** The dev/test policy of the reproduced collision (base domain shopfi.test). */
    static final CustomDomains.Policy TEST_POLICY = CustomDomains.Policy.of("shopfi.test",
            List.of("shopfi.example", "api.shopfi.example"), List.of("static.example.com"), List.of());

    /** No platform names at all: isolates the reserved-name rule. */
    static final CustomDomains.Policy EMPTY = CustomDomains.Policy.of("", List.of(), List.of(), List.of());

    private static void rejected(String raw, CustomDomains.Policy policy, String code) {
        assertThatThrownBy(() -> CustomDomains.normalize(raw, policy))
                .as("%s must be rejected with %s", raw, code)
                .isInstanceOfSatisfying(CustomDomainRejectedException.class, e -> {
                    assertThat(e.getCode()).as(raw).isEqualTo(code);
                    assertThat(e.getStatus().value()).isEqualTo(400);
                    if (raw.strip().length() >= 5) {
                        assertThat(e.getMessage()).as("never echoes the input").doesNotContain(raw.strip());
                    }
                });
    }

    // ---- normalisation ---------------------------------------------------------------------------------------------

    @Test
    void normalise_trimsLowercasesAndDropsOneTrailingDot_andIsIdempotent() {
        assertThat(CustomDomains.normalize("  Shop.Acme-Brand.COM. ", POLICY)).isEqualTo("shop.acme-brand.com");
        assertThat(CustomDomains.normalize("shop.acme-brand.com", POLICY)).isEqualTo("shop.acme-brand.com");
        assertThat(CustomDomains.normalize("\tWWW.Bravo-Goods.Net\n", POLICY)).isEqualTo("www.bravo-goods.net");
        rejected("shop.acme-brand.com..", POLICY, CustomDomains.INVALID);
    }

    @Test
    void normalise_internationalisedNamesAreStoredAsPunycode() {
        assertThat(CustomDomains.normalize("Bücher.de", POLICY)).isEqualTo("xn--bcher-kva.de");
        assertThat(CustomDomains.normalize("xn--bcher-kva.de", POLICY)).isEqualTo("xn--bcher-kva.de");
        assertThat(CustomDomains.normalize("bücher.de.", POLICY)).isEqualTo("xn--bcher-kva.de");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.co", "shop.acme-brand.com", "123shop.com", "x1.y2.z3.example-shop.org", "my-store.co.uk"})
    void valid_publicHostNamesAreAccepted(String d) {
        assertThat(CustomDomains.normalize(d, POLICY)).isEqualTo(d);
    }

    // ---- rule: required --------------------------------------------------------------------------------------------

    @Test
    void rule_required() {
        assertThatThrownBy(() -> CustomDomains.normalize(null, POLICY))
                .isInstanceOfSatisfying(CustomDomainRejectedException.class, e -> assertThat(e.getCode()).isEqualTo(CustomDomains.REQUIRED));
        rejected("", POLICY, CustomDomains.REQUIRED);
        rejected("   ", POLICY, CustomDomains.REQUIRED);
    }

    // ---- rule: IP literals (v4 in every inet_aton spelling, v6) ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.10", "10.0.0.1.", "3232235786", "0xC0A8010A", "0xc0.0xa8.0x1.0xa",
            "0300.0250.01.012", "127.1", "127.0.1", "0", "１２７.０.０.１"})
    void rule_ipv4LiteralsInAnyNotationAreRejected(String ip) {
        rejected(ip, POLICY, CustomDomains.IP_LITERAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]", "2001:db8::1", "[2001:db8::1]", "fe80::1%eth0", "::ffff:127.0.0.1", "[::ffff:7f00:1]"})
    void rule_ipv6LiteralsAreRejected(String ip) {
        rejected(ip, POLICY, CustomDomains.IP_LITERAL);
    }

    // ---- rule: syntactically valid FQDN ----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"shop", "https://shop.acme-brand.com", "shop.acme-brand.com/path", "shop.acme-brand.com:8443",
            "user@shop.acme-brand.com", "*.acme-brand.com", "shop_1.acme-brand.com", "-shop.acme-brand.com",
            "shop-.acme-brand.com", "shop..acme-brand.com", ".shop.acme-brand.com", "shop acme.com", "shop.acme.123",
            "ab--cd.acme-brand.com", "xn--abc.com", "xn--ls8h.la", "shop.acme-brand.com\u0000", "ö b.de"})
    void rule_notAValidFqdnIsRejected(String d) {
        rejected(d, POLICY, CustomDomains.INVALID);
    }

    @Test
    void rule_lengthLimits() {
        String label63 = "a".repeat(63);
        assertThat(CustomDomains.normalize(label63 + ".com", POLICY)).isEqualTo(label63 + ".com");
        rejected("a".repeat(64) + ".com", POLICY, CustomDomains.INVALID);
        String name253 = String.join(".", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(57), "com");
        assertThat(name253).hasSize(253);
        assertThat(CustomDomains.normalize(name253, POLICY)).isEqualTo(name253);
        rejected("e" + name253, POLICY, CustomDomains.INVALID);
        rejected("x".repeat(400) + ".com", POLICY, CustomDomains.INVALID);
    }

    // ---- rule: equal to / under / parent of a platform name ----------------------------------------------------------

    @Test
    void rule_reproducedCollision_ydomCannotClaimAcmeSubdomainOfTheBaseDomain() {
        // DOMAIN-01 review: tenant ydom with custom_domain=acme.shopfi.test made acme's approved Origin run as ydom
        rejected("acme.shopfi.test", TEST_POLICY, CustomDomains.PLATFORM);
        rejected("ACME.ShopFi.Test.", TEST_POLICY, CustomDomains.PLATFORM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shopfi.app", "SHOPFI.APP.", "acme.shopfi.app", "a.b.shopfi.app", "api.shopfi.app",
            "x.api.shopfi.app", "console.shopfi-admin.io", "x.console.shopfi-admin.io", "vercel.app",
            "perfume-budget-gs-fe.vercel.app", "shopfi-admin.io"})
    void rule_equalUnderOrParentOfPlatformNamesIsRejected(String d) {
        rejected(d, POLICY, CustomDomains.PLATFORM);
    }

    @Test
    void rule_parentOfTheBaseDomainIsRejected() {
        CustomDomains.Policy nested = CustomDomains.Policy.of("stores.shopfi.app", List.of(), List.of(), List.of());
        rejected("shopfi.app", nested, CustomDomains.PLATFORM);
    }

    // ---- rule: punycode / IDN confusable of a platform name --------------------------------------------------------

    @Test
    void rule_confusablesOfTheBaseDomainAreRejected_unicodeAndPunycodeForms() {
        String cyrillicO = "shоpfi.app"; // Cyrillic small o
        String puny = IDN.toASCII(cyrillicO);
        assertThat(puny).startsWith("xn--");
        rejected(cyrillicO, POLICY, CustomDomains.CONFUSABLE);
        rejected(puny, POLICY, CustomDomains.CONFUSABLE);
        rejected("acme." + puny, POLICY, CustomDomains.CONFUSABLE);
        rejected("shöpfi.app", POLICY, CustomDomains.CONFUSABLE);                // o with diaeresis
        rejected("ѕhopfi.app", POLICY, CustomDomains.CONFUSABLE);                // Cyrillic dze for s
        rejected("shopfі.app", POLICY, CustomDomains.CONFUSABLE);                // Cyrillic i
        rejected("sh0pfi.app", POLICY, CustomDomains.CONFUSABLE);                     // digit zero
        rejected("api.shοpfi.app", POLICY, CustomDomains.CONFUSABLE);           // Greek omicron
        rejected("cоnsole.shopfi-admin.io", POLICY, CustomDomains.CONFUSABLE);   // static CORS host look-alike
        // the confusable rule is about platform names only: an ordinary IDN stays valid
        assertThat(CustomDomains.normalize("shоp.de", POLICY)).startsWith("xn--");
    }

    @Test
    void rule_fullwidthFormsFoldToThePlatformNameItself() {
        rejected("ｓｈｏｐｆｉ．ａｐｐ", POLICY, CustomDomains.PLATFORM);
    }

    // ---- rule: reserved / internal names and localhost ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "LOCALHOST.", "app.localhost", "printer.local", "db.internal", "nas.lan",
            "shop.test", "x.example", "foo.invalid", "abc.onion", "router.home.arpa", "1.0.0.10.in-addr.arpa",
            "www.example.com", "example.net", "shop.example.org", "erp.corp", "wiki.intranet", "x.localdomain"})
    void rule_reservedAndInternalNamesAreRejected(String d) {
        rejected(d, EMPTY, CustomDomains.RESERVED);
    }

    @Test
    void rule_configuredExtraReservedSuffixes() {
        rejected("corp.acme-internal.com", POLICY, CustomDomains.RESERVED);
        rejected("vpn.corp.acme-internal.com", POLICY, CustomDomains.RESERVED);
        assertThat(CustomDomains.normalize("www.acme-internal.com", POLICY)).isEqualTo("www.acme-internal.com");
    }

    @Test
    void rule_localhostIsAlsoRejectedWhenItIsAConfiguredPlatformDomain() {
        rejected("localhost", POLICY, CustomDomains.RESERVED);
        rejected("shop.localhost", POLICY, CustomDomains.PLATFORM);
    }

    @Test
    void policyIgnoresIpPlatformDomainsAndBlankEntries() {
        assertThat(POLICY.platformNames()).doesNotContain("127.0.0.1").contains("shopfi.app", "localhost");
        assertThat(CustomDomains.Policy.of(null, null, null, null).platformNames()).isEmpty();
    }
}
