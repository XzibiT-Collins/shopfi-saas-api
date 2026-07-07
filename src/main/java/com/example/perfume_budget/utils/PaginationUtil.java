package com.example.perfume_budget.utils;

import com.example.perfume_budget.dto.PageResponse;
import org.springframework.data.domain.Page;

public class PaginationUtil {

    private PaginationUtil(){
        throw new IllegalStateException("Utility class");
    }

    public static <T> PageResponse<T> createPageResponse(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .totalPages(page.getTotalPages())
                .totalElements(page.getTotalElements())
                .size(page.getSize())
                .number(page.getNumber() + 1)
                .numberOfElements(page.getNumberOfElements())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .isEmpty(page.isEmpty())
                .build();
    }
}
