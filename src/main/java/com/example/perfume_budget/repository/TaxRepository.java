package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Tax;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxRepository extends JpaRepository<Tax, Long> {
    List<Tax> findAllByIsActiveTrueOrderByApplyOrderAsc();
    boolean existsByName(String name);
    boolean existsByCode(String code);
}