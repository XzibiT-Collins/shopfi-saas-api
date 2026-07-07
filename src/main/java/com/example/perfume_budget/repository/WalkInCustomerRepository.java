package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.WalkInCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WalkInCustomerRepository extends JpaRepository<WalkInCustomer, Long> {
}
