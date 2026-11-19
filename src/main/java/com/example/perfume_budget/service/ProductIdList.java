package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** CAT-07: strict parser of the {@code ids} query parameter of {@code GET /product/by-ids}. */
public final class ProductIdList {

    public static final int MAX_IDS = 12;
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

    private ProductIdList() {
    }

    /**
     * @param rawValues every occurrence of the {@code ids} parameter (each may hold a comma separated list)
     * @return the distinct ids in first-occurrence order
     * @throws BadRequestException missing/empty, more than {@value #MAX_IDS} entries, or a malformed entry
     */
    public static List<Long> parse(String[] rawValues) {
        List<String> entries = new ArrayList<>();
        if (rawValues != null) {
            for (String raw : rawValues) {
                if (raw == null) {
                    continue;
                }
                if (raw.length() > 1000) {
                    throw new BadRequestException("At most 12 ids are allowed");
                }
                for (String e : raw.split(",", -1)) {
                    entries.add(e.strip());
                }
            }
        }
        if (entries.isEmpty() || (entries.size() == 1 && entries.get(0).isEmpty())) {
            throw new BadRequestException("ids is required");
        }
        if (entries.size() > MAX_IDS) {
            throw new BadRequestException("At most 12 ids are allowed");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String e : entries) {
            if (!ID.matcher(e).matches()) {
                throw new BadRequestException("ids must be positive integers");
            }
            ids.add(Long.parseLong(e));
        }
        return List.copyOf(ids);
    }
}
