package com.example.perfume_budget.dto;

import lombok.Builder;

import java.io.Serializable;
import java.util.List;

@Builder
public record PageResponse<T>(
        List<T> content,
        int totalPages,
        long totalElements,
        int size,
        int number,
        int numberOfElements,
        boolean isFirst,
        boolean isLast,
        boolean isEmpty
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
