package com.example.perfume_budget.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "walk_in_order_taxes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalkInOrderTax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "walk_in_order_id", nullable = false)
    private WalkInOrder walkInOrder;

    @Column(nullable = false)
    private String taxName;

    @Column(nullable = false)
    private String taxCode;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal taxableAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount;
}
