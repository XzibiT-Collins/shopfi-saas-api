package com.example.perfume_budget.platform.plan.dto;

import com.example.perfume_budget.dto.json.StrictIntegerDeserializer;
import com.example.perfume_budget.platform.plan.PlainText;
import com.example.perfume_budget.platform.plan.VisibleText;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * The writable plan fields (docs/api/plans.md section 3.1; schema {@code PlanWriteFields}): the body of
 * {@code PUT /platform/plans/{id}} (with {@code version}), of {@code POST} (with {@code code}) and the {@code plan} object of
 * a matrix item. A full replace: an absent optional text is stored as null. Every bound is enforced here (bean validation,
 * Security requirement 4); integers are strict JSON integers (a fraction or a string is a 400, never truncated).
 * Required texts ({@code name}, each bullet) must contain a visible character, Unicode spaces included ({@link VisibleText});
 * optional texts made only of Unicode spaces are stored as null. Read-only properties a client may send ({@code id}, {@code currency}, the derived annual fields, {@code entitlements},
 * ...) are ignored (Jackson default), never applied.
 */
@Getter
@Setter
public class PlanWriteRequest {

    @NotNull
    @VisibleText
    @Size(max = 40)
    @PlainText
    private String name;

    @Size(max = 40)
    @PlainText
    private String tagline;

    @Size(max = 280)
    @PlainText
    private String description;

    @NotNull
    @Min(0)
    @Max(100_000_000)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer monthlyPricePesewas;

    @NotNull
    @Min(0)
    @Max(100)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer annualDiscountPercent;

    @NotNull
    @Min(0)
    @Max(365)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer trialDays;

    @NotNull
    @Min(0)
    @Max(1000)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer transactionFeeBps;

    @NotNull
    private Boolean isPublic;

    @NotNull
    private Boolean isDefaultForSignup;

    @NotNull
    private Boolean highlighted;

    @Size(max = 40)
    @PlainText
    private String badgeLabel;

    @Size(max = 80)
    @PlainText
    private String bulletsHeading;

    @NotNull
    @Size(max = 12)
    private List<@NotNull @VisibleText @Size(max = 120) @PlainText String> marketingBullets;

    @NotNull
    @Min(0)
    @Max(1000)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer sortOrder;

    @NotNull
    private Boolean active;
}
