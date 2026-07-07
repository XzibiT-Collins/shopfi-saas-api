package com.example.perfume_budget.model;

import com.example.perfume_budget.enums.JournalEntryType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_entries")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JournalEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String entryNumber; // e.g. "JE-20260301-A1B2C3"

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalEntryType type; // SALE, REFUND, DISCOUNT, TAX, ADJUSTMENT

    // reference to the source document
    private String referenceType; // "ORDER", "PAYMENT", "REFUND"
    private String referenceId;   // the order number or payment reference

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL)
    private List<JournalEntryLine> lines = new ArrayList<>();

    @Column(nullable = false)
    private Boolean isManual = false;

    private String recordedBy; // admin name who recorded it

    @Column(nullable = false)
    private LocalDateTime transactionDate;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}