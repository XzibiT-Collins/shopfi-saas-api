package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.FrontDeskUserPermissionOverride;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface FrontDeskUserPermissionOverrideRepository extends JpaRepository<FrontDeskUserPermissionOverride, Long> {
    List<FrontDeskUserPermissionOverride> findAllByUser(User user);

    /** API-RD-11d: the overrides of a whole staff list in one query. */
    @org.springframework.data.jpa.repository.Query("SELECT o FROM FrontDeskUserPermissionOverride o WHERE o.user.id IN :userIds")
    List<FrontDeskUserPermissionOverride> findAllByUserIds(@org.springframework.data.repository.query.Param("userIds") Collection<Long> userIds);
}
