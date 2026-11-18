package com.example.perfume_budget.platform.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** {@code tenant.custom-domain.*} (DOMAIN-02); env names in docs/api/custom-domains.md. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tenant.custom-domain")
public class CustomDomainProperties {

    /** Label prepended to the domain for the ownership TXT record: {@code <txt-label>.<domain>}. */
    private String txtLabel = "_shopfi-verification";

    /** Prefix of the TXT value; the full value is {@code <txt-value-prefix><token>}. */
    private String txtValuePrefix = "shopfi-verification=";

    /** Host tenants point their custom domain at (CNAME). Shown in the API response; empty = not configured. */
    private String cnameTarget = "";

    /** Extra reserved suffixes on top of the built-in list (CustomDomains.RESERVED_SUFFIXES). */
    private List<String> reservedSuffixes = new ArrayList<>();

    /** DNS servers for the TXT lookup ({@code host} or {@code host:port}); empty = the system resolver. */
    private List<String> dnsServers = new ArrayList<>();

    /** Initial per-query DNS timeout in milliseconds (JNDI doubles it on each retry). */
    private int dnsTimeoutMillis = 2000;

    /** DNS retries per server (JNDI {@code com.sun.jndi.dns.timeout.retries}). */
    private int dnsRetries = 1;

    /** Verification attempts per tenant per hour (token bucket, per JVM). */
    private int verifyPerTenantPerHour = 10;

    /** Verification attempts (DNS lookups) per minute across all tenants (per JVM). */
    private int verifyGlobalPerMinute = 60;

    /** Claim/replace/remove operations per tenant per hour (per JVM). */
    private int changesPerTenantPerHour = 20;

    /**
     * A PENDING or FAILED claim older than this many hours may be taken over by another tenant (anti-squatting); a
     * VERIFIED domain is never taken over.
     */
    private long unverifiedClaimTtlHours = 72;
}
