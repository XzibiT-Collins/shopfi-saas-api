package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.config.TenantSeeder;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Provisioning saga. PostgreSQL DDL (CREATE SCHEMA, Flyway) auto-commits outside any JPA transaction, so
 * provisioning cannot be one atomic transaction; it is a sequence of independently committed steps with
 * compensation:
 * <ol>
 *   <li>master tx #1 (REQUIRES_NEW, committed): insert the tenant as PROVISIONING (or claim a PENDING/FAILED
 *       row via compare-and-set). Visible to everyone before any DDL runs.</li>
 *   <li>DDL, no transaction: {@code CREATE SCHEMA}, then Flyway {@code migrateTenant}.</li>
 *   <li>tenant tx (inside {@link TenantSeeder}, with {@link TenantContext} bound): admin, chart of accounts,
 *       feature flags, UoM. The context is restored in {@code finally}.</li>
 *   <li>master tx #2 (REQUIRES_NEW, committed): status ACTIVE.</li>
 *   <li>On any failure after step 1: compensate with {@code DROP SCHEMA IF EXISTS ... CASCADE} (idempotent),
 *       then master tx #3 (REQUIRES_NEW, committed): status FAILED. A FAILED tenant can be provisioned again.</li>
 * </ol>
 * This service must NOT be called inside an existing transaction (it would fuse the master and tenant
 * boundaries); that is enforced. Passwords are never logged: only a pre-hashed password is accepted.
 */
@Slf4j
@Service
public class TenantProvisioningService {

    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\"',;]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,}$");
    private static final int MAX_DISPLAY_NAME = 255;
    private static final int MAX_EMAIL = 255;

    private final TenantRepository tenantRepository;
    private final TenantSchemaMigrator migrator;
    private final TenantSeeder tenantSeeder;
    private final TransactionTemplate masterTx;

    public TenantProvisioningService(TenantRepository tenantRepository, TenantSchemaMigrator migrator,
                                     TenantSeeder tenantSeeder, PlatformTransactionManager transactionManager) {
        this.tenantRepository = tenantRepository;
        this.migrator = migrator;
        this.tenantSeeder = tenantSeeder;
        this.masterTx = new TransactionTemplate(transactionManager);
        this.masterTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @throws InvalidProvisioningRequestException bad input (nothing written)
     * @throws TenantAlreadyExistsException        slug/email taken or tenant not in a provisionable state
     * @throws TenantProvisioningException         failed after registration; compensated, status FAILED, retryable
     */
    public ProvisioningResult provision(ProvisioningRequest request) {
        return provision(request, null);
    }

    /**
     * Same as {@link #provision(ProvisioningRequest)}; {@code onClaimed} (nullable) runs INSIDE master tx #1 right after
     * this caller won the claim, so whatever it writes commits atomically with the claim and only for the winner
     * (PLAT-02b: the audit INTENT row). The Tenant passed in carries the status read in the claim transaction BEFORE the move
     * to PROVISIONING. If the hook throws, the claim rolls back, nothing is provisioned and the exception propagates unchanged.
     */
    public ProvisioningResult provision(ProvisioningRequest request, java.util.function.Consumer<Tenant> onClaimed) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TenantProvisioningService must not run inside a transaction (master and tenant boundaries are separate)");
        }
        ValidatedRequest v = validate(request);

        Tenant tenant = claimOrRegister(v, onClaimed);
        Long tenantId = tenant.getId();
        log.info("Provisioning tenant {} -> schema {}", v.slug(), v.schemaName());

        TenantIdentity previous = TenantContext.get();
        try {
            migrator.createTenantSchema(v.schemaName());
            migrator.migrateTenant(v.schemaName());

            TenantContext.set(TenantIdentity.tenant(v.slug(), v.schemaName()));
            try {
                tenantSeeder.seedTenantDefaultsWithHashedAdmin(v.ownerEmail(), v.passwordHash(), v.ownerFullName());
            } finally {
                restore(previous);
            }

            masterTx.executeWithoutResult(s -> {
                int moved = tenantRepository.transitionStatus(tenantId, List.of(TenantStatus.PROVISIONING), TenantStatus.ACTIVE);
                if (moved != 1) {
                    throw new IllegalStateException("Tenant row left PROVISIONING unexpectedly");
                }
            });
            log.info("Provisioned tenant {}", v.slug());
            return new ProvisioningResult(tenantId, v.slug(), v.schemaName(), TenantStatus.ACTIVE);
        } catch (Exception e) {
            throw compensate(v, tenantId, e);
        } finally {
            restore(previous);
        }
    }

    private static void restore(TenantIdentity previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }

    /** Saga compensation. Each step is attempted regardless of the other's outcome. */
    private TenantProvisioningException compensate(ValidatedRequest v, Long tenantId, Exception cause) {
        log.error("Provisioning failed for tenant {} ({}); compensating", v.slug(), cause.getClass().getSimpleName());
        boolean dropped = true;
        try {
            migrator.dropTenantSchema(v.schemaName());
        } catch (Exception dropFailure) {
            dropped = false;
            log.error("Compensation DROP SCHEMA failed for tenant {}", v.slug(), dropFailure);
        }
        try {
            masterTx.executeWithoutResult(s ->
                    tenantRepository.transitionStatus(tenantId,
                            List.of(TenantStatus.PROVISIONING, TenantStatus.ACTIVE), TenantStatus.FAILED));
        } catch (Exception statusFailure) {
            log.error("Could not mark tenant {} FAILED; row stays PROVISIONING", v.slug(), statusFailure);
        }
        return new TenantProvisioningException(v.slug(), dropped, cause);
    }

    /** Master tx #1: insert a new PROVISIONING row, or atomically claim a PENDING/FAILED one for the same owner. */
    private Tenant claimOrRegister(ValidatedRequest v, java.util.function.Consumer<Tenant> onClaimed) {
        try {
            return masterTx.execute(s -> {
                Tenant existing = tenantRepository.findBySlug(v.slug()).orElse(null);
                if (existing == null) {
                    if (tenantRepository.existsByOwnerEmail(v.ownerEmail())) {
                        throw new TenantAlreadyExistsException("owner email already registered");
                    }
                    Tenant created = tenantRepository.saveAndFlush(Tenant.builder()
                            .slug(v.slug())
                            .displayName(v.displayName())
                            .schemaName(v.schemaName())
                            .status(TenantStatus.PROVISIONING)
                            .plan(v.plan())
                            .ownerEmail(v.ownerEmail())
                            .build());
                    runHook(onClaimed, created);
                    return created;
                }
                if (!existing.getOwnerEmail().equalsIgnoreCase(v.ownerEmail())) {
                    throw new TenantAlreadyExistsException("slug already registered");
                }
                // Exact compare-and-set from the status read in THIS transaction: `existing` keeps that pre-claim status,
                // which is what the hook (audit INTENT) must record, never a value another caller changed meanwhile.
                TenantStatus seen = existing.getStatus();
                if (seen != TenantStatus.PENDING && seen != TenantStatus.FAILED) {
                    throw new TenantAlreadyExistsException("tenant is not in a provisionable state");
                }
                int moved = tenantRepository.transitionStatus(existing.getId(), List.of(seen), TenantStatus.PROVISIONING);
                if (moved != 1) {
                    throw new TenantAlreadyExistsException("tenant is not in a provisionable state");
                }
                runHook(onClaimed, existing);
                return existing;
            });
        } catch (ClaimHookException hook) {
            throw hook.unwrap(); // a failure of the caller's hook is NOT a lost race (never reported as "already exists")
        } catch (DataIntegrityViolationException race) {
            throw new TenantAlreadyExistsException("slug or owner email already registered");
        }
    }

    /** Runs the caller's claim hook inside the claim transaction; its failure rolls the claim back and is rethrown as is. */
    private static void runHook(java.util.function.Consumer<Tenant> onClaimed, Tenant claimed) {
        if (onClaimed == null) {
            return;
        }
        try {
            onClaimed.accept(claimed);
        } catch (RuntimeException e) {
            throw new ClaimHookException(e);
        }
    }

    private static final class ClaimHookException extends RuntimeException {
        ClaimHookException(RuntimeException cause) {
            super(null, cause, false, false);
        }

        RuntimeException unwrap() {
            return (RuntimeException) getCause();
        }
    }

    private ValidatedRequest validate(ProvisioningRequest r) {
        if (r == null) {
            throw new InvalidProvisioningRequestException("request is required");
        }
        String slug = TenantSlugs.normalize(r.slug());
        String schemaName = TenantSlugs.schemaNameFor(slug);
        TenantSchemaMigrator.requireTenantSchema(schemaName);

        String email = r.ownerEmail() == null ? "" : r.ownerEmail().strip().toLowerCase(Locale.ROOT);
        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            throw new InvalidProvisioningRequestException("ownerEmail is invalid");
        }
        String hash = r.passwordHash();
        if (hash == null || !BCRYPT.matcher(hash).matches()) {
            // never echo the value: it may be a plaintext password passed by mistake
            throw new InvalidProvisioningRequestException("passwordHash must be a BCrypt hash");
        }
        String displayName = r.displayName() == null || r.displayName().isBlank() ? slug : r.displayName().strip();
        if (displayName.length() > MAX_DISPLAY_NAME || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidProvisioningRequestException("displayName is invalid");
        }
        String fullName = r.ownerFullName() == null || r.ownerFullName().isBlank() ? "Admin" : r.ownerFullName().strip();
        if (fullName.length() > MAX_DISPLAY_NAME || fullName.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidProvisioningRequestException("ownerFullName is invalid");
        }
        TenantPlan plan = r.plan() == null ? TenantPlan.FREE : r.plan();
        return new ValidatedRequest(slug, schemaName, displayName, email, fullName, hash, plan);
    }

    private record ValidatedRequest(String slug, String schemaName, String displayName, String ownerEmail,
                                    String ownerFullName, String passwordHash, TenantPlan plan) {
        @Override
        public String toString() {
            return "ValidatedRequest[slug=" + slug + "]";
        }
    }
}
