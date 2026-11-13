package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.PaymentGatewayConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentGatewayConfigRepository extends JpaRepository<PaymentGatewayConfig, Long> {

    /** Singleton config per tenant schema. */
    Optional<PaymentGatewayConfig> findTopByOrderByIdAsc();
}
