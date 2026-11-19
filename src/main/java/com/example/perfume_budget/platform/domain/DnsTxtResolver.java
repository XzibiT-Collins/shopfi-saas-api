package com.example.perfume_budget.platform.domain;

import java.util.List;

/**
 * Reads DNS TXT records (DOMAIN-02 AC2). Behind an interface so tests use a fake; production uses
 * {@link JndiDnsTxtResolver} (JDK built-in, no extra dependency).
 */
public interface DnsTxtResolver {

    /**
     * @param fqdn a name already validated by {@link CustomDomains} (plus the challenge label)
     * @return the TXT strings of {@code fqdn}, one entry per record (multi-string records concatenated, quotes removed);
     *         empty when the name or its TXT records do not exist (a definitive answer)
     * @throws DnsLookupException when no definitive answer was obtained (timeout, SERVFAIL, network error)
     */
    List<String> lookupTxt(String fqdn) throws DnsLookupException;

    /** No definitive DNS answer (the caller must not treat it as "record absent"). */
    class DnsLookupException extends Exception {
        public DnsLookupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
