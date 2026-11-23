package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.tenancy.InvalidProvisioningRequestException;
import com.example.perfume_budget.tenancy.TenantSlugs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * API-RD-04 "Find your store". NON-ENUMERATING by construction: the caller learns nothing about whether a store
 * exists. The HTTP answer is decided by the input alone (see {@code TenantOnboardingController#findStore}); what
 * differs when the store exists is an e-mail to the OWNER address on record (never to anything the caller typed),
 * carrying the store's sign-in link.
 *
 * <p>Comparable work for every input, so timing is no oracle either: exactly one registry lookup (a malformed slug
 * looks up a value that can never exist instead of skipping it) and exactly one hand-off to the mail executor (a
 * no-op task when nothing is to be sent). The send itself, including link building and SMTP, runs on that executor,
 * after the response. Only ACTIVE stores are mailed; PENDING, PROVISIONING, FAILED and SUSPENDED answer like an
 * unknown store.
 */
@Slf4j
@Service
public class StoreLookupService {

    /** Never a valid slug (leading hyphen), so the decoy lookup always misses. */
    static final String DECOY_SLUG = "-no-such-store-";
    /** Identical for every outcome. */
    public static final String MESSAGE =
            "If a store with that name exists, we've emailed its owner a link to sign in.";

    private final TenantRepository tenantRepository;
    private final EmailService emailService;
    private final MailDispatcher mailDispatcher;

    public StoreLookupService(TenantRepository tenantRepository, EmailService emailService, MailDispatcher mailDispatcher) {
        this.tenantRepository = tenantRepository;
        this.emailService = emailService;
        this.mailDispatcher = mailDispatcher;
    }

    public void requestLoginLink(String rawSlug) {
        String slug = tryNormalize(rawSlug);
        Tenant tenant = tenantRepository.findBySlug(slug == null ? DECOY_SLUG : slug).orElse(null);
        boolean send = slug != null && tenant != null && tenant.getStatus() == TenantStatus.ACTIVE;
        if (send) {
            String ownerEmail = tenant.getOwnerEmail();
            String displayName = tenant.getDisplayName();
            String tenantSlug = tenant.getSlug();
            mailDispatcher.submit("store sign-in link e-mail", "store lookup", () -> {
                log.info("Store sign-in link e-mail requested"); // never the address or the store name
                emailService.sendStoreLoginLinkEmail(ownerEmail, displayName, tenantSlug);
            });
        } else {
            mailDispatcher.submit("store sign-in link e-mail", "store lookup", () -> { });
        }
    }

    private static String tryNormalize(String raw) {
        try {
            return TenantSlugs.normalize(raw);
        } catch (InvalidProvisioningRequestException e) {
            return null;
        }
    }
}
