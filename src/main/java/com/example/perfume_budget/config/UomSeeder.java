package com.example.perfume_budget.config;

import com.example.perfume_budget.model.UnitOfMeasure;
import com.example.perfume_budget.repository.UnitOfMeasureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Seeds units of measure. Idempotent; invoked per-tenant by {@link TenantSeeder} under an
 * active {@code TenantContext} (no longer a boot-time runner).
 */
@Component
@RequiredArgsConstructor
public class UomSeeder {

    private final UnitOfMeasureRepository uomRepository;

    public void seed() {
        seedUom("EA", "Each");
        seedUom("BOX", "Box");
        seedUom("PACK", "Pack");
        seedUom("SET", "Gift Set");
        seedUom("DOZEN", "Dozen");
        seedUom("PAIR", "Pair");
    }

    private void seedUom(String code, String name) {
        if (!uomRepository.findByCode(code).isPresent()) {
            uomRepository.save(UnitOfMeasure.builder()
                    .code(code)
                    .name(name)
                    .build());
        }
    }
}
