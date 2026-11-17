package com.example.perfume_budget.outbox;

/**
 * EVT-01b (L2): the after-commit side effects that go through the tenant's {@code side_effect_outbox}. Each one is
 * its own row (and its own retry), so a failed receipt never re-sends the admin e-mail that already went out.
 */
public enum OutboxKind {
    PAYMENT_RECEIPT("customer receipt", "payment", true),
    PAYMENT_ADMIN_MAIL("admin payment e-mail", "payment", true),
    PAYMENT_ADMIN_NOTIFICATION("admin payment notification", "payment", false),
    NEW_ORDER_ADMIN_MAIL("admin new-order e-mail", "order", true);

    private final String what;
    private final String subject;
    private final boolean mail;

    OutboxKind(String what, String subject, boolean mail) {
        this.what = what;
        this.subject = subject;
        this.mail = mail;
    }

    /** What the side effect is, for logs ("customer receipt"). */
    public String what() {
        return what;
    }

    /** True: runs on the mail executor; false: on the notification executor (no mail server involved). */
    public boolean mail() {
        return mail;
    }

    /** Log-safe reference of a row: "payment 42" / "order 17" (database ids only). */
    public String describe(String reference) {
        return subject + " " + reference;
    }
}
