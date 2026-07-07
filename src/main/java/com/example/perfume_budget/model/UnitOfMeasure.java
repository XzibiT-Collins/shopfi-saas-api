package com.example.perfume_budget.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "units_of_measure")
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UnitOfMeasure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;
}
