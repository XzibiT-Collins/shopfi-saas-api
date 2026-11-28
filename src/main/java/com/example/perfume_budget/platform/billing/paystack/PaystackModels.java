package com.example.perfume_budget.platform.billing.paystack;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The summarised Paystack values the platform billing needs. Every {@code toString} omits what must not be logged (the
 * authorization code, the card fingerprint, the customer e-mail and code): a value of these types can be put in a log
 * line or an exception message safely.
 */
public final class PaystackModels {

    private PaystackModels() {
    }

    /** A reusable card authorization. {@code code} and {@code signature} are secrets (S1): never logged, never returned. */
    public record Authorization(String code, String signature, String brand, String last4, String expMonth, String expYear,
                                String bank, String channel, boolean reusable) {
        @Override
        public String toString() {
            return "Authorization[brand=" + brand + ", channel=" + channel + ", reusable=" + reusable + "]";
        }
    }

    public record Transaction(long id, String reference, String status, long amountPesewas, String currency, String channel,
                              Instant paidAt, Authorization authorization, String customerEmail, String customerCode) {
        @Override
        public String toString() {
            return "Transaction[id=" + id + ", reference=" + reference + ", status=" + status + ", amount=" + amountPesewas
                    + " " + currency + "]";
        }
    }

    /** Result of {@code transaction/initialize}: the hosted checkout URL (already checked against the allowed host). */
    public record InitializeResult(String authorizationUrl, String accessCode, String reference) {
        @Override
        public String toString() {
            return "InitializeResult[reference=" + reference + "]";
        }
    }

    public record RefundResult(long id, String status, long amountPesewas, String currency, String transactionReference,
                               String merchantNote) {
    }

    public record TransactionPage(List<Transaction> items, int page, int perPage, long total) {
    }

    /** {@code metadata} is sent as Paystack metadata: our own ids only, never a card or secret (the client does not check). */
    public record InitializeCommand(String email, long amountPesewas, String reference, Map<String, String> metadata,
                                    String callbackUrl) {
        @Override
        public String toString() {
            return "InitializeCommand[reference=" + reference + ", amount=" + amountPesewas + "]";
        }
    }

    public record ChargeCommand(String email, long amountPesewas, String authorizationCode, String reference) {
        @Override
        public String toString() {
            return "ChargeCommand[reference=" + reference + ", amount=" + amountPesewas + "]";
        }
    }

    /** {@code amountPesewas == null}: refund the full transaction. {@code merchantNote} is our refund key. */
    public record RefundCommand(String transactionReference, Long amountPesewas, String merchantNote) {
    }

    public record TransactionQuery(Instant from, Instant to, String status, int perPage, int page) {
    }
}
