package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.StockConversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockConversionRepository extends JpaRepository<StockConversion, Long> {
    Optional<StockConversion> findByConversionNumber(String conversionNumber);
}
