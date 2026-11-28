package com.example.perfume_budget.platform.billing.settings;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import com.example.perfume_budget.platform.billing.credentials.CredentialStatus;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response shapes of the settings and credentials endpoints (schema {@code BillingSettingsView}, {@code CredentialStatus}, ...). */
public final class BillingViews {

    private BillingViews() {
    }

    /**
     * One environment's keys as the API may show them: {@code configured}, the last 4 characters and metadata. There is no
     * field that could hold a key, so no code path can serialise one.
     */
    @JsonPropertyOrder({"environment", "configured", "status", "publicKeyLast4", "secretKeyLast4", "verifiedAt", "failureCode",
            "lastWebhookAt", "lastWebhookOkAt", "webhookUrl", "version", "updatedAt", "updatedBy"})
    public record CredentialStatusView(BillingEnvironment environment, boolean configured, CredentialStatus status,
                                       String publicKeyLast4, String secretKeyLast4, Instant verifiedAt, String failureCode,
                                       Instant lastWebhookAt, Instant lastWebhookOkAt, String webhookUrl, int version,
                                       Instant updatedAt, String updatedBy) {
    }

    public record CredentialListView(List<CredentialStatusView> credentials) {
    }

    public record HealthView(boolean paused, String reason, Instant since, int affectedSubscriptions) {
    }

    @JsonPropertyOrder({"settings", "version", "updatedAt", "updatedBy", "credentials", "numberPreviews", "health"})
    public record BillingSettingsView(BillingSettingsData settings, int version, Instant updatedAt, String updatedBy,
                                      List<CredentialStatusView> credentials, Map<String, String> numberPreviews,
                                      HealthView health) {
    }

    public record SettingsAuditRowView(long id, Instant occurredAt, long actorId, String actorEmail, int settingsVersion,
                                       JsonNode changes) {
    }

    public record SettingsAuditPageView(List<SettingsAuditRowView> items, int page, int size, long totalElements,
                                        int totalPages) {
    }
}
