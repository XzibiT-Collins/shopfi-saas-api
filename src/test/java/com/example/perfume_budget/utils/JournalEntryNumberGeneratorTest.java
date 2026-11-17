package com.example.perfume_budget.utils;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** ORD-02 rework: entry numbers stay unique when many postings run in the same millisecond. */
class JournalEntryNumberGeneratorTest {

    @Test
    void numbersGeneratedInTheSameMillisecondAreUnique() {
        JournalEntryNumberGenerator g = new JournalEntryNumberGenerator();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 20_000).parallel().forEach(i -> seen.add(g.generate()));
        assertThat(seen).hasSize(20_000);
        assertThat(seen).allMatch(n -> n.matches("JE\\d+-[0-9a-f]{8}") && n.length() <= 255);
    }
}
