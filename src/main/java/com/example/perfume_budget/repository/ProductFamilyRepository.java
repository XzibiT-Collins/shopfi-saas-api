package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.ProductFamily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductFamilyRepository extends JpaRepository<ProductFamily, Long> {
    Optional<ProductFamily> findByFamilyCode(String familyCode);
}
