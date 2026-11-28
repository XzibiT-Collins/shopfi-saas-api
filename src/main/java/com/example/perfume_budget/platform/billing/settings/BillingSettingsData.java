package com.example.perfume_budget.platform.billing.settings;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The platform billing settings as the API shows them (schema {@code BillingSettings}, billing.md 4.1): an immutable
 * value, used for the view, the parsed request and the audit diff. Property order is the contract's.
 */
@JsonPropertyOrder({"billingEnabled", "activeEnvironment", "cardRequiredAtSignup", "graceDays", "retryOffsetsDays",
        "retryTimeOfDayUtc", "trialReminderDaysBefore", "renewalReminderDaysBefore", "priceChangeNoticeDays",
        "graceReminderOffsetsDays", "graceEndingReminderDaysBefore", "emailEnabled", "invoiceNumberFormat",
        "receiptNumberFormat", "creditNoteNumberFormat", "businessName", "businessAddress", "businessTin", "businessEmail",
        "businessPhone", "taxLabel", "taxRateBps", "taxInclusive", "invoiceFooterText", "attachPdfToEmail",
        "cardVerificationAmountPesewas", "allowedChannels", "recurringChannels", "reconcileIntervalMinutes",
        "reconcileLookbackHours", "reconcileFullScanDays", "billingRunIntervalSeconds", "webhookIpAllowlistEnabled",
        "webhookIpAllowlist", "refundMaxDays", "cardTokenRetentionDaysAfterEnd", "oneTrialPerCard",
        "cardAttemptsPerEmailPerDay", "cardDeclineAlertThresholdPerHour", "cardSetupStopThresholdPerHour",
        "refundConfirmThresholdPesewas"})
public record BillingSettingsData(
        boolean billingEnabled,
        BillingEnvironment activeEnvironment,
        boolean cardRequiredAtSignup,
        int graceDays,
        List<Integer> retryOffsetsDays,
        String retryTimeOfDayUtc,
        List<Integer> trialReminderDaysBefore,
        List<Integer> renewalReminderDaysBefore,
        int priceChangeNoticeDays,
        List<Integer> graceReminderOffsetsDays,
        List<Integer> graceEndingReminderDaysBefore,
        Map<String, Boolean> emailEnabled,
        String invoiceNumberFormat,
        String receiptNumberFormat,
        String creditNoteNumberFormat,
        String businessName,
        String businessAddress,
        String businessTin,
        String businessEmail,
        String businessPhone,
        String taxLabel,
        Integer taxRateBps,
        boolean taxInclusive,
        String invoiceFooterText,
        boolean attachPdfToEmail,
        long cardVerificationAmountPesewas,
        List<String> allowedChannels,
        List<String> recurringChannels,
        int reconcileIntervalMinutes,
        int reconcileLookbackHours,
        int reconcileFullScanDays,
        int billingRunIntervalSeconds,
        boolean webhookIpAllowlistEnabled,
        List<String> webhookIpAllowlist,
        int refundMaxDays,
        int cardTokenRetentionDaysAfterEnd,
        boolean oneTrialPerCard,
        int cardAttemptsPerEmailPerDay,
        int cardDeclineAlertThresholdPerHour,
        int cardSetupStopThresholdPerHour,
        long refundConfirmThresholdPesewas) {
}
