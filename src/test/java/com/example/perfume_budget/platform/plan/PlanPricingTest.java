package com.example.perfume_budget.platform.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLAN-01 AC5 / plans.md section 3.2 / Security M3: the ONE derived-price function. Pins every worked row of the contract
 * table (seed, half-up edge cases incl. the per-month .5 cases, and the bound where an int intermediate overflows), reads
 * the table from the doc so doc and code cannot drift, and cross-checks a sweep against an independent BigDecimal model.
 */
class PlanPricingTest {

    @ParameterizedTest(name = "{0} pesewas at {1}% -> {2} / {3} / {4}")
    @CsvSource({
            "14900, 20, 143040, 11920, 35760",      // seed Starter
            "39900, 20, 383040, 31920, 95760",      // seed Growth
            "99900, 20, 959040, 79920, 239760",     // seed Scale
            "44900, 15, 457980, 38165, 80820",      // example plan-updated
            "14901, 20, 143050, 11921, 35762",      // annual rounds up
            "14999, 15, 152990, 12749, 26998",
            "12345, 17, 122956, 10246, 25184",      // annual rounds down
            "14902, 23, 137694, 11475, 41130",      // per-month exactly .5: HALF_UP 11475 (HALF_EVEN would give 11474)
            "1, 50, 6, 1, 6",                       // smallest .5 case
            "14900, 0, 178800, 14900, 0",
            "14900, 100, 0, 0, 178800",
            "0, 20, 0, 0, 0",
            "100000000, 0, 1200000000, 100000000, 0",          // bound: intermediate 120,000,000,000 overflows int
            "100000000, 1, 1188000000, 99000000, 12000000",
            "100000000, 20, 960000000, 80000000, 240000000",
            "99999999, 33, 803999992, 66999999, 395999996"})
    void contractTableRows(long monthly, int pct, long annual, long perMonth, long saving) {
        PlanPricing.Derived d = PlanPricing.derive(monthly, pct);
        assertThat(d.annualPricePesewas()).isEqualTo(annual);
        assertThat(d.annualMonthlyEquivalentPesewas()).isEqualTo(perMonth);
        assertThat(d.annualSavingPesewas()).isEqualTo(saving);
    }

    /** Every row of the plans.md 3.2 table, parsed from the doc itself. */
    @Test
    void everyWorkedRowOfTheContractTableMatches() throws Exception {
        String doc = Files.readString(Path.of("docs/api/plans.md"));
        Matcher m = Pattern.compile("(?m)^\\| (\\d+) \\| (\\d+) \\| [0-9.]+ \\| \\**(\\d+)\\** \\| [0-9.]+(?:\\.\\.\\.)?\\** \\| \\**(\\d+)\\** \\| \\**(\\d+)\\** \\|")
                .matcher(doc.replace("**0.5**", "0.5").replace("**11474.5**", "11474.5"));
        int rows = 0;
        while (m.find()) {
            rows++;
            PlanPricing.Derived d = PlanPricing.derive(Long.parseLong(m.group(1)), Integer.parseInt(m.group(2)));
            assertThat(d.annualPricePesewas()).as(m.group()).isEqualTo(Long.parseLong(m.group(3)));
            assertThat(d.annualMonthlyEquivalentPesewas()).as(m.group()).isEqualTo(Long.parseLong(m.group(4)));
            assertThat(d.annualSavingPesewas()).as(m.group()).isEqualTo(Long.parseLong(m.group(5)));
        }
        assertThat(rows).as("rows of the 3.2 worked-values table").isEqualTo(16);
    }

    @Test
    void atTheBoundNothingOverflowsOrGoesNegative() {
        for (int pct = 0; pct <= 100; pct++) {
            PlanPricing.Derived d = PlanPricing.derive(PlanPricing.MAX_MONTHLY_PESEWAS, pct);
            assertThat(d.annualPricePesewas()).isBetween(0L, 1_200_000_000L);
            assertThat(d.annualSavingPesewas()).isBetween(0L, 1_200_000_000L);
            assertThat(d.annualPricePesewas() + d.annualSavingPesewas()).isEqualTo(1_200_000_000L);
            assertThat(d.annualMonthlyEquivalentPesewas()).isBetween(0L, 100_000_000L);
        }
        // the int-arithmetic version of the formula really would be wrong here (guards the test's own premise)
        int monthly = 100_000_000;
        assertThat(monthly * 12 * 100 / 100).isNotEqualTo(1_200_000_000);
    }

    @Test
    void sweepAgreesWithAnIndependentHalfUpModel() {
        long[] prices = {0, 1, 2, 7, 99, 101, 149, 14900, 14901, 14902, 33333, 99999, 1_000_001, 99_999_999, 100_000_000};
        for (long monthly : prices) {
            for (int pct = 0; pct <= 100; pct++) {
                BigDecimal exact = BigDecimal.valueOf(monthly).multiply(BigDecimal.valueOf(12L * (100 - pct)))
                        .divide(BigDecimal.valueOf(100), 10, RoundingMode.UNNECESSARY);
                long annual = exact.setScale(0, RoundingMode.HALF_UP).longValueExact();
                long perMonth = BigDecimal.valueOf(annual).divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP).longValueExact();
                PlanPricing.Derived d = PlanPricing.derive(monthly, pct);
                assertThat(d.annualPricePesewas()).as(monthly + "/" + pct).isEqualTo(annual);
                assertThat(d.annualMonthlyEquivalentPesewas()).as(monthly + "/" + pct).isEqualTo(perMonth);
                assertThat(d.annualSavingPesewas()).as(monthly + "/" + pct).isEqualTo(monthly * 12 - annual);
            }
        }
    }

    @Test
    void outOfRangeInputIsRefused() {
        assertThatThrownBy(() -> PlanPricing.derive(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPricing.derive(100_000_001L, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPricing.derive(100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPricing.derive(100, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
