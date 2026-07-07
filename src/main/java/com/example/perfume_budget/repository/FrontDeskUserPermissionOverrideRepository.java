package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.FrontDeskUserPermissionOverride;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FrontDeskUserPermissionOverrideRepository extends JpaRepository<FrontDeskUserPermissionOverride, Long> {
    List<FrontDeskUserPermissionOverride> findAllByUser(User user);
}
