package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.CustomerSortField;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.projection.CustomerOrderSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * API-RD-10: the customer metrics list (search + sort + deterministic order). The sort expression comes from the
 * {@link CustomerSortField} whitelist and the direction from {@link Sort.Direction}; the search text only ever travels
 * as a bind parameter (with LIKE wildcards escaped), never inside the query string.
 */
@Repository
public class CustomerMetricsQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /** Escapes {@code \}, {@code %} and {@code _} so user text is matched literally by {@code LIKE ... ESCAPE '\'}. */
    public static String likePattern(String search) {
        String escaped = search.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    @Transactional(readOnly = true)
    public Page<CustomerOrderSummary> findCustomers(String search, CustomerSortField sort, Sort.Direction direction,
                                                    Pageable pageable) {
        boolean filtered = search != null && !search.isBlank();
        String where = "WHERE u.roles = :role" + (filtered
                ? " AND (LOWER(u.fullName) LIKE :q ESCAPE '\\' OR LOWER(u.email) LIKE :q ESCAPE '\\')" : "");

        // LEFT JOIN: a registered customer with no order is listed (orderCount 0). u.id ASC is the tie-breaker.
        String jpql = """
                SELECT u.id, u.fullName, u.email, u.isActive, u.createdAt, COUNT(o.id),
                       %s, MAX(o.createdAt)
                FROM User u LEFT JOIN Order o ON o.user = u
                %s
                GROUP BY u.id, u.fullName, u.email, u.isActive, u.createdAt
                ORDER BY %s %s NULLS LAST, u.id ASC
                """.formatted(CustomerSortField.TOTAL_SPENT.jpql(), where, sort.jpql(),
                direction == Sort.Direction.ASC ? "ASC" : "DESC");

        TypedQuery<Object[]> query = em.createQuery(jpql, Object[].class).setParameter("role", UserRole.CUSTOMER);
        TypedQuery<Long> count = em.createQuery("SELECT COUNT(u.id) FROM User u " + where, Long.class)
                .setParameter("role", UserRole.CUSTOMER);
        if (filtered) {
            String pattern = likePattern(search.trim());
            query.setParameter("q", pattern);
            count.setParameter("q", pattern);
        }
        query.setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize());

        List<CustomerOrderSummary> rows = query.getResultList().stream().<CustomerOrderSummary>map(Row::of).toList();
        return new PageImpl<>(rows, pageable, count.getSingleResult());
    }

    private record Row(Long userId, String fullName, String email, boolean isActive, LocalDateTime createdAt,
                       Long totalOrders, BigDecimal totalAmountSpent, LocalDateTime lastOrderAt)
            implements CustomerOrderSummary {
        static Row of(Object[] r) {
            return new Row((Long) r[0], (String) r[1], (String) r[2], Boolean.TRUE.equals(r[3]), (LocalDateTime) r[4],
                    (Long) r[5], r[6] == null ? BigDecimal.ZERO : new BigDecimal(r[6].toString()), (LocalDateTime) r[7]);
        }

        @Override public Long getUserId() { return userId; }
        @Override public String getFullName() { return fullName; }
        @Override public String getEmail() { return email; }
        @Override public boolean getIsActive() { return isActive; }
        @Override public LocalDateTime getCreatedAt() { return createdAt; }
        @Override public Long getTotalOrders() { return totalOrders; }
        @Override public BigDecimal getTotalAmountSpent() { return totalAmountSpent; }
        @Override public LocalDateTime getLastOrderAt() { return lastOrderAt; }
    }
}
