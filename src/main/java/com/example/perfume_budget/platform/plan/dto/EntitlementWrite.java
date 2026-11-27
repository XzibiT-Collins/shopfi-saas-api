package com.example.perfume_budget.platform.plan.dto;

import com.example.perfume_budget.dto.json.StrictIntegerDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

/**
 * One entitlement change of a matrix item (schema {@code EntitlementWrite}). BOOLEAN: {@code enabled}, limitValue null or
 * absent. LIMIT: {@code limitValue} (integer >= 0, null = unlimited), {@code enabled} ignored. Whether
 * {@code limitValue} was SENT is tracked: for a LIMIT feature an absent value is a 400, never read as "unlimited".
 */
@Getter
public class EntitlementWrite {

    @NotNull
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$", message = "unknown feature")
    private String featureKey;

    private Boolean enabled;

    @Min(0)
    private Integer limitValue;

    @JsonIgnore
    private boolean limitValueSent;

    public void setFeatureKey(String featureKey) {
        this.featureKey = featureKey;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    @JsonSetter("limitValue")
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    public void setLimitValue(Integer limitValue) {
        this.limitValue = limitValue;
        this.limitValueSent = true;
    }
}
