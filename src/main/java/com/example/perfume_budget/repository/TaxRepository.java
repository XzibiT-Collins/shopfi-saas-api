package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Tax;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxRepository extends JpaRepository<Tax, Long> {
    List<Tax> findAllByIsActiveTrueOrderByApplyOrderAsc();
    boolean existsByName(String name);
    boolean existsByCode(String code);

    /** API-RD-11c: row lock for the If-Match check and the write of one tax save. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tax t WHERE t.id = :id")
    Optional<Tax> lockById(@Param("id") Long id);
}