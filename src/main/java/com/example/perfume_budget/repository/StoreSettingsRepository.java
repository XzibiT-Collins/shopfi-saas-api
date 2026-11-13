package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.StoreSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoreSettingsRepository extends JpaRepository<StoreSettings, Long> {

    /** Singleton settings row per tenant schema. */
    Optional<StoreSettings> findTopByOrderByIdAsc();
}
