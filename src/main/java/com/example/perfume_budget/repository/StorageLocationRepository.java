package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.StorageLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {
    List<StorageLocation> findAllByOrderByNameAsc();

    Optional<StorageLocation> findByIsDefaultReceivingTrue();

    Optional<StorageLocation> findByIsWalkInSaleSourceTrue();

    Optional<StorageLocation> findByIsEcommerceFulfilmentSourceTrue();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
