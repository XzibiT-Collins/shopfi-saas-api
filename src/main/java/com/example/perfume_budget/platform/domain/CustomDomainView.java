package com.example.perfume_budget.platform.domain;

import java.time.Instant;

/**
 * A tenant's custom domain and what the owner must publish in DNS (docs/api/custom-domains.md). All fields except
 * {@code cnameTarget} are null when no domain is set. The TXT value is not a secret (it only proves DNS control).
 *
 * @param txtRecordName  {@code <tenant.custom-domain.txt-label>.<domain>}
 * @param txtRecordValue the exact TXT value to publish
 * @param cnameTarget    where the domain's traffic must point (CNAME); null when not configured
 * @param failureReason  why the last check failed ({@code TXT_RECORD_NOT_FOUND} or a {@code DOMAIN_*} validation code)
 */
public record CustomDomainView(String domain, String status, String txtRecordName, String txtRecordValue,
                               String cnameTarget, Instant requestedAt, Instant checkedAt, Instant verifiedAt,
                               String failureReason) {
}
