package com.example.perfume_budget.platform.billing.fake;

/**
 * The magic cards of the fake hosted checkout (billing.md 19.1). The card chosen on the checkout page decides what the
 * authorization it mints does later (the fake's authorization codes remember their scenario). These are documentation
 * values: they mean nothing to any real provider and no real card or key exists anywhere in the fake.
 */
public enum FakeCard {
    SUCCESS("4084084084084081", "visa", "408408", "4081", true),
    INSUFFICIENT_FUNDS("4084080000005408", "visa", "408408", "5408", true),
    DECLINED("4084080000000409", "visa", "408408", "0409", true),
    TIMEOUT("4084080000000002", "mastercard", "408408", "0002", true),
    SERVER_ERROR("4084080000000500", "mastercard", "408408", "0500", true),
    WRONG_AMOUNT("4084080000000600", "visa", "408408", "0600", true),
    WRONG_CURRENCY("4084080000000700", "visa", "408408", "0700", true),
    NON_REUSABLE("4084080000000800", "visa", "408408", "0800", false),
    DELAYED("4084080000000900", "visa", "408408", "0900", true);

    public final String number;
    public final String brand;
    public final String bin;
    public final String last4;
    public final boolean reusable;

    FakeCard(String number, String brand, String bin, String last4, boolean reusable) {
        this.number = number;
        this.brand = brand;
        this.bin = bin;
        this.last4 = last4;
        this.reusable = reusable;
    }
}
