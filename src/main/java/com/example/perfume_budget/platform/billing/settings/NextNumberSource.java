package com.example.perfume_budget.platform.billing.settings;

/**
 * The number each document type would issue NEXT in a year (billing.md 4.1 {@code numberPreviews}). BILL-05 (invoices)
 * provides the real counters; until then {@link FirstNumberSource} answers 1, which is exactly the next number of a platform
 * that has issued nothing.
 */
public interface NextNumberSource {

    enum DocumentType { INVOICE, RECEIPT, CREDIT_NOTE }

    long next(DocumentType type, int year);
}
