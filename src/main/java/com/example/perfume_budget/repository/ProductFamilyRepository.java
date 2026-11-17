package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.ProductFamily;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductFamilyRepository extends JpaRepository<ProductFamily, Long> {
    Optional<ProductFamily> findByFamilyCode(String familyCode);

    /*
     * CAT-02: open-in-view is off and ProductFamily.baseUnit is LAZY: the admin family reads map the base unit outside a
     * transaction, so load it in the same query (entity graph = one left join, no N+1).
     */
    @EntityGraph(attributePaths = "baseUnit")
    @Query("select f from ProductFamily f")
    List<ProductFamily> findAllWithBaseUnit();

    @EntityGraph(attributePaths = "baseUnit")
    Optional<ProductFamily> findWithBaseUnitById(Long id);
}
