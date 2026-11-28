package com.example.perfume_budget.platform.billing.credentials;

import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.billing.BillingEnvironment;
import com.example.perfume_budget.platform.billing.BillingErrors;
import com.example.perfume_budget.platform.billing.BillingProperties;
import com.example.perfume_budget.platform.billing.paystack.CredentialAccess;
import com.example.perfume_budget.platform.billing.paystack.PaystackPlatformClient;
import com.example.perfume_budget.platform.billing.paystack.PaystackResult;
import com.example.perfume_budget.platform.billing.settings.BillingSettings;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsAuditLog;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsAuditRepository;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsRepository;
import com.example.perfume_budget.platform.billing.settings.BillingViews.CredentialStatusView;
import com.example.perfume_budget.platform.plan.PlanValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Per-environment Paystack credentials of the platform (billing.md 4.4, S2). WRITE-ONLY: the secret is encrypted with
 * {@link BillingSecretCipher} (versioned key, AAD = table, environment and field) and no method returns it to an API; the
 * only reader of the plaintext is {@link #secretKey}, which feeds the Paystack client's Bearer header.
 *
 * <p>Writes lock the environment's row (bounded wait) and compare {@code expectedVersion} themselves: a stale value is 409
 * {@code CREDENTIALS_VERSION_CONFLICT}, never a lock error. Every write and every status change adds an audit row in the
 * SAME transaction whose fields are "unset" / "set" / "changed" / last 4 / status, never a key.
 */
@Slf4j
@Service
public class BillingCredentialService implements CredentialAccess {

    private static final Pattern KEY_BODY = Pattern.compile("[A-Za-z0-9]{16,128}");
    private static final String TABLE = "billing_paystack_credentials";
    private static final String SECRET_FIELD = "secret_key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BillingCredentialRepository credentials;
    private final BillingSettingsRepository settingsRepository;
    private final BillingSettingsAuditRepository audit;
    private final BillingSecretCipher cipher;
    private final BillingProperties props;
    private final PaystackPlatformClient client;
    private final Clock clock;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final TransactionTemplate requiresNew;

    public BillingCredentialService(BillingCredentialRepository credentials, BillingSettingsRepository settingsRepository,
                                    BillingSettingsAuditRepository audit, BillingSecretCipher cipher, BillingProperties props,
                                    PaystackPlatformClient client, Clock clock, EntityManager em,
                                    PlatformTransactionManager txm) {
        this.credentials = credentials;
        this.settingsRepository = settingsRepository;
        this.audit = audit;
        this.cipher = cipher;
        this.props = props;
        this.client = client;
        this.clock = clock;
        this.em = em;
        this.tx = new TransactionTemplate(txm);
        this.requiresNew = new TransactionTemplate(txm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------------------------------ reads

    public List<CredentialStatusView> list() {
        return tx.execute(s -> credentials.all().stream().map(this::view).toList());
    }

    public CredentialStatusView get(BillingEnvironment environment) {
        return tx.execute(s -> view(credentials.findById(environment).orElseThrow()));
    }

    private CredentialStatusView view(BillingCredential c) {
        return CredentialViews.of(c, props);
    }

    /** The decrypted secret for the Paystack client's Bearer header. Never log or return it. */
    @Override
    public Optional<String> secretKey(BillingEnvironment environment) {
        return tx.execute(s -> credentials.findById(environment)
                .filter(BillingCredential::isConfigured)
                .map(c -> cipher.decrypt(c.getSecretKeyEncrypted(), aad(environment))));
    }

    /** The stored public key (not a secret): the hosted checkout needs it. */
    public Optional<String> publicKey(BillingEnvironment environment) {
        return tx.execute(s -> credentials.findById(environment).filter(BillingCredential::isConfigured).map(BillingCredential::getPublicKey));
    }

    // ------------------------------------------------------------------------------------------ write

    /** {@code PUT /credentials/{environment}}: the body is read as a JSON tree (strict types, no coercion). */
    public CredentialStatusView put(BillingEnvironment environment, JsonNode request, PlatformPrincipal actor) {
        Map<String, String> errors = new LinkedHashMap<>();
        String publicKey = key(request, "publicKey", environment.publicKeyPrefix(), environment, errors);
        String secretKey = key(request, "secretKey", environment.secretKeyPrefix(), environment, errors);
        Integer expected = null;
        JsonNode ev = request.get("expectedVersion");
        if (ev != null && !ev.isNull()) {
            if (!ev.isIntegralNumber() || !ev.canConvertToInt() || ev.intValue() < 0) {
                errors.put("expectedVersion", "must be a non-negative whole number");
            } else {
                expected = ev.intValue();
            }
        }
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }
        Integer expectedVersion = expected;
        return tx.execute(s -> {
            em.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
            BillingCredential row = credentials.lock(environment);
            if (expectedVersion != null && expectedVersion != row.getVersion()) {
                throw BillingErrors.credentialsVersionConflict(expectedVersion, row.getVersion());
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            String before = row.isConfigured() ? "set" : "unset";
            String beforeSecretLast4 = row.getSecretLast4();
            row.setPublicKey(publicKey);
            row.setPublicLast4(last4(publicKey));
            row.setSecretKeyEncrypted(cipher.encrypt(secretKey, aad(environment)));
            row.setSecretKeyVersion((short) cipher.currentVersion());
            row.setSecretLast4(last4(secretKey));
            row.setStatus(CredentialStatus.UNVERIFIED);
            row.setStatusChangedAt(now);
            row.setVerifiedAt(null);
            row.setFailureCode(null);
            row.setVersion(row.getVersion() + 1);
            row.setUpdatedAt(now);
            row.setUpdatedByAdminId(actor.id());
            row.setUpdatedByEmail(actor.email());
            credentials.saveAndFlush(row);

            ArrayNode changes = JSON.createArrayNode();
            String prefix = "credentials." + environment.name() + ".";
            change(changes, prefix + "secretKey", before, "changed");
            change(changes, prefix + "secretKeyLast4", beforeSecretLast4, row.getSecretLast4());
            audit(actor.id(), actor.email(), changes);
            return view(row);
        });
    }

    // ------------------------------------------------------------------------------------------ verify

    /**
     * {@code POST /credentials/{environment}/verify}: one cheap authenticated call with THAT environment's key, made outside
     * any transaction (no row lock is held across the network). Accepted: VERIFIED. Rejected (401/403): FAILED
     * {@code KEY_REJECTED}. Anything else (timeout, circuit open, 5xx) leaves the status alone and is 503.
     */
    public CredentialStatusView verify(BillingEnvironment environment, PlatformPrincipal actor) {
        int versionRead = tx.execute(s -> {
            BillingCredential c = credentials.findById(environment).orElseThrow();
            if (!c.isConfigured()) {
                throw BillingErrors.credentialsNotConfigured();
            }
            return c.getVersion();
        });
        PaystackResult<?> result = client.ping(environment);
        if (result instanceof PaystackResult.NotConfigured) {
            throw BillingErrors.credentialsNotConfigured();
        }
        boolean accepted = result instanceof PaystackResult.Ok;
        boolean rejected = result instanceof PaystackResult.KeyRejected || result instanceof PaystackResult.Rejected;
        if (!accepted && !rejected) {
            throw BillingErrors.providerUnavailable();
        }
        return tx.execute(s -> {
            em.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
            BillingCredential row = credentials.lock(environment);
            if (row.getVersion() != versionRead || !row.isConfigured()) {
                return view(row); // the keys were replaced while we were calling Paystack: that answer is about the old pair
            }
            applyStatus(row, accepted ? CredentialStatus.VERIFIED : CredentialStatus.FAILED, accepted ? null : "KEY_REJECTED",
                    actor.id(), actor.email());
            return view(row);
        });
    }

    /** A Paystack 401 / 403 on any billing call (platform fault): FAILED, recorded as the system actor. Idempotent. */
    @Override
    public void keyRejected(BillingEnvironment environment) {
        try {
            requiresNew.executeWithoutResult(s -> {
                em.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
                BillingCredential row = credentials.lock(environment);
                if (row.isConfigured() && row.getStatus() != CredentialStatus.FAILED) {
                    applyStatus(row, CredentialStatus.FAILED, "KEY_REJECTED", 0L, "system");
                }
            });
        } catch (RuntimeException e) {
            log.error("Could not record the rejected Paystack key of {}: {}", environment, e.getClass().getSimpleName());
        }
    }

    private void applyStatus(BillingCredential row, CredentialStatus status, String failureCode, long actorId, String actorEmail) {
        CredentialStatus before = row.getStatus();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        row.setStatus(status);
        row.setFailureCode(failureCode);
        row.setVerifiedAt(status == CredentialStatus.VERIFIED ? now : null);
        if (before != status) {
            row.setStatusChangedAt(now);
        }
        credentials.saveAndFlush(row);
        ArrayNode changes = JSON.createArrayNode();
        change(changes, "credentials." + row.getEnvironment().name() + ".status", before.name(), status.name());
        audit(actorId, actorEmail, changes);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private String key(JsonNode request, String field, String prefix, BillingEnvironment environment, Map<String, String> errors) {
        JsonNode n = request.get(field);
        String example = field.equals("publicKey") ? "public key (pk_test_ or pk_live_" : "secret key (sk_test_ or sk_live_";
        String generic = "must be a Paystack " + example + " followed by 16 to 128 letters and digits)";
        if (n == null || !n.isTextual()) {
            errors.put(field, generic);
            return null;
        }
        String v = n.textValue();
        String general = field.equals("publicKey") ? "pk_" : "sk_";
        boolean shape = (v.startsWith(general + "test_") && KEY_BODY.matcher(v.substring((general + "test_").length())).matches())
                || (v.startsWith(general + "live_") && KEY_BODY.matcher(v.substring((general + "live_").length())).matches());
        if (!shape) {
            errors.put(field, generic);
            return null;
        }
        if (!v.startsWith(prefix)) {
            errors.put(field, "must carry the key prefix of the " + environment.pathValue() + " environment");
            return null;
        }
        return v;
    }

    private static String last4(String key) {
        return key.substring(key.length() - 4);
    }

    private String aad(BillingEnvironment environment) {
        return BillingSecretCipher.aad(TABLE, environment.name(), SECRET_FIELD);
    }

    private static void change(ArrayNode changes, String field, Object before, Object after) {
        ObjectNode n = changes.addObject();
        n.put("field", field);
        n.set("before", JSON.valueToTree(before));
        n.set("after", JSON.valueToTree(after));
    }

    private void audit(long actorId, String actorEmail, ArrayNode changes) {
        BillingSettings settings = settingsRepository.row();
        audit.saveAndFlush(BillingSettingsAuditLog.builder().actorAdminId(actorId).actorEmail(actorEmail)
                .settingsVersion(settings.getVersion()).changes(changes).build());
    }
}
