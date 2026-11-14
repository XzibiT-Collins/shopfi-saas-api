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
}
