package com.example.perfume_budget.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "taxes")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // e.g. "VAT", "NHIL", "GetFund Levy"

    @Column(nullable = false)
    private String code; // e.g. "VAT", "NHIL", "GFL"

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal rate; // e.g. 15.00 for 15%

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isCompound = false; // if true, applied on top of previous taxes

    @Builder.Default
    @Column(nullable = false)
    private Integer applyOrder = 0; // order in which tax is applied

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}