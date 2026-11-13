package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    /** Existence check for theme references (THEME-02): which of {@code ids} exist in the current tenant. */
    @org.springframework.data.jpa.repository.Query("select c.id from Category c where c.id in :ids")
    java.util.List<Long> findExistingIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    boolean existsByName(String categoryName);
}
