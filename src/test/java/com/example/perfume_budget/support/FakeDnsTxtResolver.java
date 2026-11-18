package com.example.perfume_budget.support;

import com.example.perfume_budget.platform.domain.DnsTxtResolver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DOMAIN-02 test double for DNS: TXT records live in a map; a name in {@link #failing} answers like a timeout. Registered
 * as the @Primary {@link DnsTxtResolver} in every Postgres test context, so no test ever queries real DNS.
 */
public class FakeDnsTxtResolver implements DnsTxtResolver {

    private final Map<String, List<String>> records = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final AtomicInteger lookups = new AtomicInteger();

    @Override
    public List<String> lookupTxt(String fqdn) throws DnsLookupException {
        lookups.incrementAndGet();
        if (failing.contains(fqdn)) {
            throw new DnsLookupException("fake timeout", null);
        }
        return records.getOrDefault(fqdn, List.of());
    }

    public void put(String fqdn, String... values) {
        records.put(fqdn, List.of(values));
    }

    public void remove(String fqdn) {
        records.remove(fqdn);
    }

    public void fail(String fqdn) {
        failing.add(fqdn);
    }

    public int lookups() {
        return lookups.get();
    }

    public void reset() {
        records.clear();
        failing.clear();
        lookups.set(0);
    }
}
