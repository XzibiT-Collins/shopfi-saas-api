package com.example.perfume_budget.repository;


import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * Find user by OAuth2 provider and provider ID
     * Used for OAuth2 authentication to check if user already exists
     */
    Optional<User> findByAuthProviderAndProviderId(AuthProvider authProvider, String providerId);

    /**
     * Check if a user exists with a given provider and provider ID
     */
    boolean existsByAuthProviderAndProviderId(AuthProvider authProvider, String providerId);

    long countByRolesNot(UserRole role);

    List<User> findAllByRoles(UserRole role);
    List<User> findAllByRolesIn (Collection<UserRole> roles);

    @Query("""
        SELECT u FROM User u
        LEFT JOIN u.profile p
        WHERE u.roles = :role
        AND (
            LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))
        )
        ORDER BY u.fullName ASC
        """)
    List<User> searchUsersByNameOrEmailAndRole(@Param("query") String query, @Param("role") UserRole role);
}
