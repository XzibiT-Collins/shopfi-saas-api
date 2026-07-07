package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.DeliveryDetail;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DeliveryDetailRepository extends JpaRepository<DeliveryDetail, Long> {
    @Modifying
    @Transactional
    @Query("UPDATE DeliveryDetail d SET d.isDefault = false WHERE d.user.id = :userId")
    int clearDefaultForUser(@Param("userId") Long userId);

    Optional<DeliveryDetail> findByIdAndUser(Long deliveryDetailId, User currentUser);

    List<DeliveryDetail> findAllByUser(User currentUser);

    Optional<DeliveryDetail> findByUserAndIsDefaultTrue(User user);
}
