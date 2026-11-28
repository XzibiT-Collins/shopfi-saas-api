package com.example.perfume_budget.platform.billing.settings;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code master.billing_settings}: the ONE row (a CHECK pins {@code id = 1}) of every platform billing setting. Writes go
 * through {@link BillingSettingsService}, which row-locks it and compares {@code version} itself, so a stale write is
 * a 409 and never a Hibernate optimistic-lock failure (503 TRY_AGAIN).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "billing_settings", schema = "master")
public class BillingSettings {

    public static final short ROW_ID = 1;

    @Id
    private Short id;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by_admin_id")
    private Long updatedByAdminId;

    @Column(name = "updated_by_email")
    private String updatedByEmail;

    @Column(name = "billing_enabled", nullable = false)
    private boolean billingEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "active_environment", nullable = false, length = 4)
    private BillingEnvironment activeEnvironment;

    @Column(name = "card_required_at_signup", nullable = false)
    private boolean cardRequiredAtSignup;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_offsets_days", nullable = false, columnDefinition = "jsonb")
    private List<Integer> retryOffsetsDays;

    @Column(name = "retry_time_of_day_utc", length = 5)
    private String retryTimeOfDayUtc;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trial_reminder_days_before", nullable = false, columnDefinition = "jsonb")
    private List<Integer> trialReminderDaysBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "renewal_reminder_days_before", nullable = false, columnDefinition = "jsonb")
    private List<Integer> renewalReminderDaysBefore;

    @Column(name = "price_change_notice_days", nullable = false)
    private int priceChangeNoticeDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grace_reminder_offsets_days", nullable = false, columnDefinition = "jsonb")
    private List<Integer> graceReminderOffsetsDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grace_ending_reminder_days_before", nullable = false, columnDefinition = "jsonb")
    private List<Integer> graceEndingReminderDaysBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "email_enabled", nullable = false, columnDefinition = "jsonb")
    private Map<String, Boolean> emailEnabled;

    @Column(name = "invoice_number_format", nullable = false, length = 40)
    private String invoiceNumberFormat;

    @Column(name = "receipt_number_format", nullable = false, length = 40)
    private String receiptNumberFormat;

    @Column(name = "credit_note_number_format", nullable = false, length = 40)
    private String creditNoteNumberFormat;

    @Column(name = "business_name", length = 120)
    private String businessName;

    @Column(name = "business_address", length = 300)
    private String businessAddress;

    @Column(name = "business_tin", length = 40)
    private String businessTin;

    @Column(name = "business_email", length = 254)
    private String businessEmail;

    @Column(name = "business_phone", length = 40)
    private String businessPhone;

    @Column(name = "tax_label", length = 40)
    private String taxLabel;

    @Column(name = "tax_rate_bps")
    private Integer taxRateBps;

    @Column(name = "tax_inclusive", nullable = false)
    private boolean taxInclusive;

    @Column(name = "invoice_footer_text", length = 500)
    private String invoiceFooterText;

    @Column(name = "attach_pdf_to_email", nullable = false)
    private boolean attachPdfToEmail;

    @Column(name = "card_verification_amount_pesewas", nullable = false)
    private long cardVerificationAmountPesewas;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_channels", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedChannels;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recurring_channels", nullable = false, columnDefinition = "jsonb")
    private List<String> recurringChannels;

    @Column(name = "reconcile_interval_minutes", nullable = false)
    private int reconcileIntervalMinutes;

    @Column(name = "reconcile_lookback_hours", nullable = false)
    private int reconcileLookbackHours;

    @Column(name = "reconcile_full_scan_days", nullable = false)
    private int reconcileFullScanDays;

    @Column(name = "billing_run_interval_seconds", nullable = false)
    private int billingRunIntervalSeconds;

    @Column(name = "webhook_ip_allowlist_enabled", nullable = false)
    private boolean webhookIpAllowlistEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "webhook_ip_allowlist", nullable = false, columnDefinition = "jsonb")
    private List<String> webhookIpAllowlist;

    @Column(name = "refund_max_days", nullable = false)
    private int refundMaxDays;

    @Column(name = "refund_confirm_threshold_pesewas", nullable = false)
    private long refundConfirmThresholdPesewas;

    @Column(name = "card_token_retention_days_after_end", nullable = false)
    private int cardTokenRetentionDaysAfterEnd;

    @Column(name = "one_trial_per_card", nullable = false)
    private boolean oneTrialPerCard;

    @Column(name = "card_attempts_per_email_per_day", nullable = false)
    private int cardAttemptsPerEmailPerDay;

    @Column(name = "card_decline_alert_threshold_per_hour", nullable = false)
    private int cardDeclineAlertThresholdPerHour;

    @Column(name = "card_setup_stop_threshold_per_hour", nullable = false)
    private int cardSetupStopThresholdPerHour;

    public BillingSettingsData toData() {
        return new BillingSettingsData(billingEnabled, activeEnvironment, cardRequiredAtSignup, graceDays,
                List.copyOf(retryOffsetsDays), retryTimeOfDayUtc, List.copyOf(trialReminderDaysBefore),
                List.copyOf(renewalReminderDaysBefore), priceChangeNoticeDays, List.copyOf(graceReminderOffsetsDays),
                List.copyOf(graceEndingReminderDaysBefore), orderedEmailFlags(emailEnabled), invoiceNumberFormat,
                receiptNumberFormat, creditNoteNumberFormat, businessName, businessAddress, businessTin, businessEmail,
                businessPhone, taxLabel, taxRateBps, taxInclusive, invoiceFooterText, attachPdfToEmail,
                cardVerificationAmountPesewas, List.copyOf(allowedChannels), List.copyOf(recurringChannels),
                reconcileIntervalMinutes, reconcileLookbackHours, reconcileFullScanDays, billingRunIntervalSeconds,
                webhookIpAllowlistEnabled, List.copyOf(webhookIpAllowlist), refundMaxDays, cardTokenRetentionDaysAfterEnd,
                oneTrialPerCard, cardAttemptsPerEmailPerDay, cardDeclineAlertThresholdPerHour,
                cardSetupStopThresholdPerHour, refundConfirmThresholdPesewas);
    }

    public void apply(BillingSettingsData d) {
        billingEnabled = d.billingEnabled();
        activeEnvironment = d.activeEnvironment();
        cardRequiredAtSignup = d.cardRequiredAtSignup();
        graceDays = d.graceDays();
        retryOffsetsDays = List.copyOf(d.retryOffsetsDays());
        retryTimeOfDayUtc = d.retryTimeOfDayUtc();
        trialReminderDaysBefore = List.copyOf(d.trialReminderDaysBefore());
        renewalReminderDaysBefore = List.copyOf(d.renewalReminderDaysBefore());
        priceChangeNoticeDays = d.priceChangeNoticeDays();
        graceReminderOffsetsDays = List.copyOf(d.graceReminderOffsetsDays());
        graceEndingReminderDaysBefore = List.copyOf(d.graceEndingReminderDaysBefore());
        emailEnabled = new java.util.LinkedHashMap<>(d.emailEnabled());
        invoiceNumberFormat = d.invoiceNumberFormat();
        receiptNumberFormat = d.receiptNumberFormat();
        creditNoteNumberFormat = d.creditNoteNumberFormat();
        businessName = d.businessName();
        businessAddress = d.businessAddress();
        businessTin = d.businessTin();
        businessEmail = d.businessEmail();
        businessPhone = d.businessPhone();
        taxLabel = d.taxLabel();
        taxRateBps = d.taxRateBps();
        taxInclusive = d.taxInclusive();
        invoiceFooterText = d.invoiceFooterText();
        attachPdfToEmail = d.attachPdfToEmail();
        cardVerificationAmountPesewas = d.cardVerificationAmountPesewas();
        allowedChannels = List.copyOf(d.allowedChannels());
        recurringChannels = List.copyOf(d.recurringChannels());
        reconcileIntervalMinutes = d.reconcileIntervalMinutes();
        reconcileLookbackHours = d.reconcileLookbackHours();
        reconcileFullScanDays = d.reconcileFullScanDays();
        billingRunIntervalSeconds = d.billingRunIntervalSeconds();
        webhookIpAllowlistEnabled = d.webhookIpAllowlistEnabled();
        webhookIpAllowlist = List.copyOf(d.webhookIpAllowlist());
        refundMaxDays = d.refundMaxDays();
        refundConfirmThresholdPesewas = d.refundConfirmThresholdPesewas();
        cardTokenRetentionDaysAfterEnd = d.cardTokenRetentionDaysAfterEnd();
        oneTrialPerCard = d.oneTrialPerCard();
        cardAttemptsPerEmailPerDay = d.cardAttemptsPerEmailPerDay();
        cardDeclineAlertThresholdPerHour = d.cardDeclineAlertThresholdPerHour();
        cardSetupStopThresholdPerHour = d.cardSetupStopThresholdPerHour();
    }

    /** The flags in the contract's enum order, whatever order the database returned them in. */
    static Map<String, Boolean> orderedEmailFlags(Map<String, Boolean> stored) {
        Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (com.example.perfume_budget.platform.billing.NotificationKind k : com.example.perfume_budget.platform.billing.NotificationKind.values()) {
            out.put(k.name(), Boolean.TRUE.equals(stored.get(k.name())));
        }
        return out;
    }
}
