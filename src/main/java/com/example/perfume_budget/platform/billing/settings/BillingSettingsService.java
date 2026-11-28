package com.example.perfume_budget.platform.billing.settings;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.billing.BillingClock;
import com.example.perfume_budget.platform.billing.BillingEnvironment;
import com.example.perfume_budget.platform.billing.BillingErrors;
import com.example.perfume_budget.platform.billing.BillingProperties;
import com.example.perfume_budget.platform.billing.credentials.BillingCredential;
import com.example.perfume_budget.platform.billing.credentials.BillingCredentialRepository;
import com.example.perfume_budget.platform.billing.credentials.CredentialStatus;
import com.example.perfume_budget.platform.billing.credentials.CredentialViews;
import com.example.perfume_budget.platform.billing.settings.BillingViews.BillingSettingsView;
import com.example.perfume_budget.platform.billing.settings.BillingViews.HealthView;
import com.example.perfume_budget.platform.billing.settings.BillingViews.SettingsAuditPageView;
import com.example.perfume_budget.platform.billing.settings.BillingViews.SettingsAuditRowView;
import com.example.perfume_budget.platform.plan.PlanValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform billing settings (billing.md 4.1-4.3, 4.6): the one versioned, audited row.
 *
 * <p>A save runs in ONE transaction: parse and validate the body (400, every violation at once, the rules that need stored
 * state merged into the same field map), lock the row (bounded wait), compare {@code version} (409
 * {@code BILLING_SETTINGS_VERSION_CONFLICT} with both versions, never the generic 503), then write, bump the version and add
 * the audit row with field-level before / after. A body equal to the stored values writes nothing (no bump, no audit row).
 */
@Service
public class BillingSettingsService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE = 1_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BillingSettingsRepository repository;
    private final BillingCredentialRepository credentials;
    private final BillingSettingsAuditRepository auditRepository;
    private final NextNumberSource numbers;
    private final BillingProperties props;
    private final BillingClock billingClock;
    private final Clock clock;
    private final EntityManager em;

    public BillingSettingsService(BillingSettingsRepository repository, BillingCredentialRepository credentials,
                                  BillingSettingsAuditRepository auditRepository, NextNumberSource numbers,
                                  BillingProperties props, BillingClock billingClock, Clock clock, EntityManager em) {
        this.repository = repository;
        this.credentials = credentials;
        this.auditRepository = auditRepository;
        this.numbers = numbers;
        this.props = props;
        this.billingClock = billingClock;
        this.clock = clock;
        this.em = em;
    }

    /** The current settings (read side for the other billing code: the client reads the allowed channels from here). */
    @Transactional(readOnly = true)
    public BillingSettingsData current() {
        return repository.row().toData();
    }

    @Transactional(readOnly = true)
    public BillingSettingsView view() {
        return buildView(repository.row());
    }

    @Transactional
    public BillingSettingsView put(JsonNode request, PlatformPrincipal actor) {
        BillingSettingsParser.Parsed parsed = BillingSettingsParser.parse(request, true);
        em.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
        BillingSettings row = repository.lockRow();
        Map<String, String> errors = new LinkedHashMap<>(parsed.errors());
        stateRules(parsed, row, errors);
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }
        if (parsed.version() != row.getVersion()) {
            throw BillingErrors.settingsVersionConflict(parsed.version(), row.getVersion());
        }
        BillingSettingsData before = row.toData();
        ArrayNode changes = diff(before, parsed.data());
        if (changes.isEmpty()) {
            return buildView(row);
        }
        row.apply(parsed.data());
        row.setVersion(row.getVersion() + 1);
        row.setUpdatedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        row.setUpdatedByAdminId(actor.id());
        row.setUpdatedByEmail(actor.email());
        repository.saveAndFlush(row);
        auditRepository.saveAndFlush(BillingSettingsAuditLog.builder().actorAdminId(actor.id()).actorEmail(actor.email())
                .settingsVersion(row.getVersion()).changes(changes).build());
        return buildView(row);
    }

    /**
     * 4.2 rules 2: the active environment must have VERIFIED credentials (checked when it CHANGES, so the untouched default
     * does not block saving other settings), and billing can only be turned ON with a VERIFIED active environment.
     */
    private void stateRules(BillingSettingsParser.Parsed parsed, BillingSettings row, Map<String, String> errors) {
        Map<BillingEnvironment, CredentialStatus> status = new EnumMap<>(BillingEnvironment.class);
        credentials.all().forEach(c -> status.put(c.getEnvironment(), c.getStatus()));
        BillingEnvironment target = parsed.activeEnvironment();
        if (target != null && target != row.getActiveEnvironment() && status.get(target) != CredentialStatus.VERIFIED) {
            errors.putIfAbsent("activeEnvironment", "the environment's credentials are not verified");
        }
        if (Boolean.TRUE.equals(parsed.billingEnabled()) && !row.isBillingEnabled() && target != null
                && status.get(target) != CredentialStatus.VERIFIED) {
            errors.putIfAbsent("billingEnabled", "billing cannot be enabled until the active environment's credentials are verified");
        }
    }

    /** {@code POST /settings/number-preview}: any subset of the three formats, no state change, no counter consumed. */
    @Transactional(readOnly = true)
    public Map<String, String> numberPreview(JsonNode request) {
        Map<String, String> errors = new LinkedHashMap<>();
        BillingSettings row = repository.row();
        String invoice = previewFormat(request, "invoiceNumberFormat", row.getInvoiceNumberFormat(), errors);
        String receipt = previewFormat(request, "receiptNumberFormat", row.getReceiptNumberFormat(), errors);
        String credit = previewFormat(request, "creditNoteNumberFormat", row.getCreditNoteNumberFormat(), errors);
        if (!request.has("invoiceNumberFormat") && !request.has("receiptNumberFormat") && !request.has("creditNoteNumberFormat")) {
            errors.put("invoiceNumberFormat", "send at least one number format");
        }
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }
        return previews(invoice, receipt, credit);
    }

    private static String previewFormat(JsonNode request, String field, String saved, Map<String, String> errors) {
        JsonNode n = request.get(field);
        if (n == null) {
            return saved;
        }
        if (!n.isTextual() || !NumberFormats.isValid(n.textValue())) {
            errors.put(field, NumberFormats.MESSAGE);
            return saved;
        }
        return n.textValue();
    }

    @Transactional(readOnly = true)
    public SettingsAuditPageView audit(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (p > MAX_PAGE) {
            throw new BadRequestException("page must be <= " + MAX_PAGE);
        }
        if (size != null && size < 1) {
            throw new BadRequestException("size must be >= 1");
        }
        int s = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<BillingSettingsAuditLog> result = auditRepository.newestFirst(PageRequest.of(p, s));
        return new SettingsAuditPageView(result.getContent().stream().map(a -> new SettingsAuditRowView(a.getId(),
                a.getOccurredAt(), a.getActorAdminId(), a.getActorEmail(), a.getSettingsVersion(), ordered(a.getChanges()))).toList(),
                p, s, result.getTotalElements(), result.getTotalPages());
    }

    // ------------------------------------------------------------------------------------------ view and diff

    /** jsonb does not keep key order: every change is shown as {field, before, after}, the contract's order. */
    private static JsonNode ordered(JsonNode stored) {
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode c : stored) {
            ObjectNode n = out.addObject();
            n.set("field", c.get("field"));
            n.set("before", c.get("before"));
            n.set("after", c.get("after"));
        }
        return out;
    }

    private BillingSettingsView buildView(BillingSettings row) {
        var creds = credentials.all();
        return new BillingSettingsView(row.toData(), row.getVersion(), row.getUpdatedAt(), row.getUpdatedByEmail(),
                creds.stream().map(c -> CredentialViews.of(c, props)).toList(),
                previews(row.getInvoiceNumberFormat(), row.getReceiptNumberFormat(), row.getCreditNoteNumberFormat()),
                health(row, creds));
    }

    private Map<String, String> previews(String invoice, String receipt, String credit) {
        int year = billingClock.now().atZone(ZoneOffset.UTC).getYear();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("INVOICE", NumberFormats.render(invoice, year, numbers.next(NextNumberSource.DocumentType.INVOICE, year)));
        out.put("RECEIPT", NumberFormats.render(receipt, year, numbers.next(NextNumberSource.DocumentType.RECEIPT, year)));
        out.put("CREDIT_NOTE", NumberFormats.render(credit, year, numbers.next(NextNumberSource.DocumentType.CREDIT_NOTE, year)));
        return out;
    }

    /**
     * Platform-fault indicator (billing.md 4.4, 20.3 item 9): paused while the ACTIVE environment's keys are rejected. The
     * "billing off while billing-managed subscriptions exist" reason and the affected count arrive with the subscription
     * state of BILL-01b; until subscriptions exist the count is 0.
     */
    private HealthView health(BillingSettings row, java.util.List<BillingCredential> creds) {
        for (BillingCredential c : creds) {
            if (c.getEnvironment() == row.getActiveEnvironment() && c.getStatus() == CredentialStatus.FAILED) {
                return new HealthView(true, "KEY_REJECTED", c.getStatusChangedAt(), 0);
            }
        }
        return new HealthView(false, null, null, 0);
    }

    /** Field-level before / after of the changed settings; {@code emailEnabled} per notification kind. */
    static ArrayNode diff(BillingSettingsData before, BillingSettingsData after) {
        ObjectNode b = JSON.valueToTree(before);
        ObjectNode a = JSON.valueToTree(after);
        ArrayNode out = JSON.createArrayNode();
        Iterator<String> names = a.fieldNames();
        while (names.hasNext()) {
            String f = names.next();
            if (f.equals("emailEnabled")) {
                Iterator<String> kinds = a.get(f).fieldNames();
                while (kinds.hasNext()) {
                    String k = kinds.next();
                    if (!a.get(f).get(k).equals(b.get(f).get(k))) {
                        add(out, f + "." + k, b.get(f).get(k), a.get(f).get(k));
                    }
                }
            } else if (!a.get(f).equals(b.get(f))) {
                add(out, f, b.get(f), a.get(f));
            }
        }
        return out;
    }

    private static void add(ArrayNode out, String field, JsonNode before, JsonNode after) {
        ObjectNode n = out.addObject();
        n.put("field", field);
        n.set("before", before);
        n.set("after", after);
    }
}
