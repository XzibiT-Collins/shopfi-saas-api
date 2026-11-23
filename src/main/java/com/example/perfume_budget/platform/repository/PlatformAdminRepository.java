package com.example.perfume_budget.platform.repository;

import com.example.perfume_budget.platform.model.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {

    /** Case-insensitive (lower(email) = lower(?)): consistent with login lowercasing; V5 enforces lower(email) uniqueness. */
    Optional<PlatformAdmin> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Row lock (SELECT ... FOR UPDATE): two concurrent password changes of one admin serialise on it (API-RD-07). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from PlatformAdmin a where a.id = :id")
    Optional<PlatformAdmin> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
