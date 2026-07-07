package com.example.perfume_budget.utils;

import com.example.perfume_budget.dto.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaginationUtilTest {

    @Test
    void createPageResponse_Success() {
        List<String> content = List.of("item1", "item2");
        Page<String> page = new PageImpl<>(content, PageRequest.of(0, 10), 100);

        PageResponse<String> response = PaginationUtil.createPageResponse(page);

        assertNotNull(response);
        assertEquals(content, response.content());
        assertEquals(10, response.totalPages());
        assertEquals(100, response.totalElements());
        assertEquals(1, response.number()); // number + 1
        assertEquals(2, response.numberOfElements());
        assertTrue(response.isFirst());
        assertFalse(response.isLast());
        assertFalse(response.isEmpty());
    }

    @Test
    void createPageResponse_EmptyPage() {
        Page<String> page = Page.empty();
        PageResponse<String> response = PaginationUtil.createPageResponse(page);
        
        assertTrue(response.isEmpty());
        assertEquals(0, response.totalElements());
    }
}
