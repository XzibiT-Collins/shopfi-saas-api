package com.example.perfume_budget.platform.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * THE derived-price function (docs/api/plans.md section 3.2, owner O5). Every plan response, platform and public, takes
 * its annual figures from here and nowhere else.
 *
 * <pre>
 * annualPricePesewas             = round_half_up(monthly * 12 * (100 - percent) / 100)
 * annualMonthlyEquivalentPesewas = round_half_up(annualPricePesewas / 12)
 * annualSavingPesewas            = monthly * 12 - annualPricePesewas
 * </pre>
 * Every intermediate is a {@code long} or a {@link BigDecimal} with {@link RoundingMode#HALF_UP}; never an {@code int} and
 * never floating point: at the bound {@code monthly * 12 * 100} is 120,000,000,000, which overflows an int (Security M3).
 */
public final class PlanPricing {

    public static final long MAX_MONTHLY_PESEWAS = 100_000_000L;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PlanPricing() {
    }

    public record Derived(long annualPricePesewas, long annualMonthlyEquivalentPesewas, long annualSavingPesewas) {
    }

    public static Derived derive(long monthlyPricePesewas, int annualDiscountPercent) {
        if (monthlyPricePesewas < 0 || monthlyPricePesewas > MAX_MONTHLY_PESEWAS) {
            throw new IllegalArgumentException("monthly price out of range");
        }
        if (annualDiscountPercent < 0 || annualDiscountPercent > 100) {
            throw new IllegalArgumentException("annual discount out of range");
        }
        BigDecimal yearly = BigDecimal.valueOf(monthlyPricePesewas).multiply(TWELVE);
        long annual = yearly.multiply(BigDecimal.valueOf(100L - annualDiscountPercent))
                .divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
        long perMonth = BigDecimal.valueOf(annual).divide(TWELVE, 0, RoundingMode.HALF_UP).longValueExact();
        long saving = yearly.longValueExact() - annual;
        return new Derived(annual, perMonth, saving);
    }
}
