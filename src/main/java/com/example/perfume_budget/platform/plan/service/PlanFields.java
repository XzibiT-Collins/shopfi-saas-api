package com.example.perfume_budget.platform.plan.service;

import com.example.perfume_budget.platform.plan.VisibleText;
import com.example.perfume_budget.platform.plan.dto.PlanWriteRequest;
import com.example.perfume_budget.platform.plan.model.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The writable fields of one plan after normalisation (plans.md section 1: text trimmed of every Unicode space, a blank optional text stored as
 * null). Built from a VALIDATED request (bean validation already rejected Cc / Cf / U+2028 / U+2029 on the raw value, so
 * trimming can never hide a forbidden character) or from a stored plan; compared field by field for the no-op rule and
 * the audit diff.
 */
record PlanFields(String name, String tagline, String description, long monthlyPricePesewas, int annualDiscountPercent,
                  int trialDays, int transactionFeeBps, boolean isPublic, boolean isDefaultForSignup, boolean highlighted,
                  String badgeLabel, String bulletsHeading, List<String> marketingBullets, int sortOrder,
                  boolean active) {

    static PlanFields of(PlanWriteRequest r) {
        List<String> bullets = new ArrayList<>();
        for (String b : r.getMarketingBullets()) {
            bullets.add(VisibleText.Validator.strip(b));
        }
        return new PlanFields(VisibleText.Validator.strip(r.getName()), optional(r.getTagline()), optional(r.getDescription()),
                r.getMonthlyPricePesewas(), r.getAnnualDiscountPercent(), r.getTrialDays(), r.getTransactionFeeBps(),
                r.getIsPublic(), r.getIsDefaultForSignup(), r.getHighlighted(), optional(r.getBadgeLabel()),
                optional(r.getBulletsHeading()), List.copyOf(bullets), r.getSortOrder(), r.getActive());
    }

    static PlanFields of(Plan p) {
        return new PlanFields(p.getName(), p.getTagline(), p.getDescription(), p.getMonthlyPricePesewas(),
                p.getAnnualDiscountPercent(), p.getTrialDays(), p.getTransactionFeeBps(), p.isPublic(), p.isDefaultForSignup(),
                p.isHighlighted(), p.getBadgeLabel(), p.getBulletsHeading(), List.copyOf(p.getMarketingBullets()),
                p.getSortOrder(), p.isActive());
    }

    PlanFields withDefaultForSignup(boolean value) {
        return new PlanFields(name, tagline, description, monthlyPricePesewas, annualDiscountPercent, trialDays,
                transactionFeeBps, isPublic, value, highlighted, badgeLabel, bulletsHeading, marketingBullets, sortOrder,
                active);
    }

    void applyTo(Plan p) {
        p.setName(name);
        p.setTagline(tagline);
        p.setDescription(description);
        p.setMonthlyPricePesewas(monthlyPricePesewas);
        p.setAnnualDiscountPercent((short) annualDiscountPercent);
        p.setTrialDays(trialDays);
        p.setTransactionFeeBps(transactionFeeBps);
        p.setPublic(isPublic);
        p.setDefaultForSignup(isDefaultForSignup);
        p.setHighlighted(highlighted);
        p.setBadgeLabel(badgeLabel);
        p.setBulletsHeading(bulletsHeading);
        p.setMarketingBullets(new ArrayList<>(marketingBullets));
        p.setSortOrder(sortOrder);
        p.setActive(active);
    }

    /** JSON name -> value, in contract field order (the derived annual fields are never audited). */
    Map<String, Object> asJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("tagline", tagline);
        m.put("description", description);
        m.put("monthlyPricePesewas", monthlyPricePesewas);
        m.put("annualDiscountPercent", annualDiscountPercent);
        m.put("trialDays", trialDays);
        m.put("transactionFeeBps", transactionFeeBps);
        m.put("isPublic", isPublic);
        m.put("isDefaultForSignup", isDefaultForSignup);
        m.put("highlighted", highlighted);
        m.put("badgeLabel", badgeLabel);
        m.put("bulletsHeading", bulletsHeading);
        m.put("marketingBullets", marketingBullets);
        m.put("sortOrder", sortOrder);
        m.put("active", active);
        return m;
    }

    private static String optional(String s) {
        if (s == null) {
            return null;
        }
        String t = VisibleText.Validator.strip(s); // Unicode-aware: NBSP / U+2003 / U+3000-only is blank -> null
        return t.isEmpty() ? null : t;
    }
}
