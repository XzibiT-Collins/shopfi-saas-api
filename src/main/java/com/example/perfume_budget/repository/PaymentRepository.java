package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Payment findByProviderPaymentReference(String reference);

    boolean existsBySystemReference(String reference);

    Optional<Payment> findByOrder_OrderNumber(String orderNumber);

    /** Owner-scoped lookup: a reference that belongs to someone else's order is indistinguishable from an unknown one. */
    @Query("""
        SELECT p FROM Payment p JOIN FETCH p.order o
        WHERE p.providerPaymentReference = :reference AND o.user = :user
        """)
    Optional<Payment> findOwnedByProviderReference(@Param("reference") String reference, @Param("user") User user);

    @Query("SELECT p.order.id FROM Payment p WHERE p.providerPaymentReference = :reference")
    Optional<Long> findOrderIdByProviderReference(@Param("reference") String reference);

    @Query("SELECT p FROM Payment p WHERE p.order IN :orders")
    List<Payment> findAllByOrderIn(@Param("orders") Collection<Order> orders);

    /** Atomically claims the single provider-verification slot of an interval; 1 = this caller may call the provider. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Payment p SET p.lastProviderCheckAt = :now
        WHERE p.id = :id AND (p.lastProviderCheckAt IS NULL OR p.lastProviderCheckAt < :cutoff)
        """)
    int claimProviderCheck(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);
}
