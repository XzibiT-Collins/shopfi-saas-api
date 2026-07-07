package com.example.perfume_budget.platform.repository;

import com.example.perfume_budget.platform.model.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {

    Optional<PlatformAdmin> findByEmail(String email);

    boolean existsByEmail(String email);
}
