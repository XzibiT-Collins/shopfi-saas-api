package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.FrontDeskPermissionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FrontDeskPermissionTemplateRepository extends JpaRepository<FrontDeskPermissionTemplate, Long> {
    List<FrontDeskPermissionTemplate> findAllByEnabledTrue();
}
