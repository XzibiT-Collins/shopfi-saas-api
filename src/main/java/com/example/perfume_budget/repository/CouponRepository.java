package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCode(String upperCase);

    boolean existsByCode(String code);

//    Page<Coupon> findAll(Pageable pageable);
}
