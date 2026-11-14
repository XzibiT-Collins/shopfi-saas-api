package com.example.perfume_budget.platform.repository;

import com.example.perfume_budget.platform.model.TenantSignup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface TenantSignupRepository extends JpaRepository<TenantSignup, Long> {

    /** Row lock so concurrent verify attempts serialise and cannot dodge the attempt counter. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TenantSignup s where s.tenantId = :tenantId")
    Optional<TenantSignup> findByTenantIdForUpdate(@Param("tenantId") Long tenantId);

    Optional<TenantSignup> findByTenantId(Long tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TenantSignup s where s.tenantId = :tenantId")
    int deleteByTenantId(@Param("tenantId") Long tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenantSignup s set s.verifiedAt = null where s.tenantId = :tenantId")
    int clearVerified(@Param("tenantId") Long tenantId);
}
