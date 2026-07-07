package com.example.perfume_budget.repository;

import com.example.perfume_budget.model.SiteVisit;
import com.example.perfume_budget.projection.PageVisitMetric;
import com.example.perfume_budget.projection.SiteVisitProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SiteVisitRepository extends JpaRepository<SiteVisit, Long> {
    boolean existsByIpAddressAndVisitDateAndPage(String ipAddress, LocalDate visitDate, String page);
    long countByVisitDate(LocalDate date);
    long countByVisitDateBetween(LocalDate start, LocalDate end);

    @Query("""
        SELECT s.page AS page, COUNT(DISTINCT s.ipAddress) AS uniqueVisits
        FROM SiteVisit s
        WHERE s.visitDate BETWEEN :start AND :end
        GROUP BY s.page
        ORDER BY uniqueVisits DESC
        LIMIT 5
        """)
    List<PageVisitMetric> findTop5MostVisitedPages(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT s.page, COUNT(s) as visits FROM SiteVisit s " +
            "WHERE s.visitDate BETWEEN :start AND :end " +
            "GROUP BY s.page ORDER BY visits DESC")
    List<Object[]> findMostVisitedPagesBetween(@Param("start") LocalDate start,
                                               @Param("end") LocalDate end);

    @Query("SELECT COUNT(DISTINCT s.ipAddress) FROM SiteVisit s")
    long countAllTimeUniqueVisitors();

    @Query("SELECT COUNT(DISTINCT s.ipAddress) FROM SiteVisit s WHERE s.visitDate BETWEEN :start AND :end")
    long countUniqueVisitorsBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
        SELECT COUNT(s) AS totalVisits,
               COUNT(DISTINCT s.ipAddress) AS totalUniqueVisitors,
               COUNT(DISTINCT s.page) AS totalPagesVisited
        FROM SiteVisit s
        WHERE s.visitDate BETWEEN :start AND :end
        """)
    SiteVisitProjection getSiteVisitMetrics(@Param("start") LocalDate start,
                                            @Param("end") LocalDate end);
}
