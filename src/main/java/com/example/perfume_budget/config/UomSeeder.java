package com.example.perfume_budget.config;

import com.example.perfume_budget.model.UnitOfMeasure;
import com.example.perfume_budget.repository.UnitOfMeasureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Order(2) // Run after ChartOfAccountsSeeder
public class UomSeeder implements CommandLineRunner {

    private final UnitOfMeasureRepository uomRepository;

    @Override
    public void run(String... args) {
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
