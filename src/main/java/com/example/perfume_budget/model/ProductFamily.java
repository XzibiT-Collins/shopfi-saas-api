package com.example.perfume_budget.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "product_families", indexes = {
    @Index(name = "idx_family_code", columnList = "family_code")
})
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductFamily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_code", unique = true, nullable = false)
    private String familyCode;

    @Column(nullable = false)
    private String name;

    private String brand;

    // Direct reference to the base product (EA)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_unit_id")
    private Product baseUnit;
}
