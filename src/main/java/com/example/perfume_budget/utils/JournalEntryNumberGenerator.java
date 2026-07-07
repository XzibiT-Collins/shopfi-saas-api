package com.example.perfume_budget.utils;

import org.springframework.stereotype.Component;

@Component
public class JournalEntryNumberGenerator {
    public String generate() {
        return "JE" + System.currentTimeMillis();
    }
}
