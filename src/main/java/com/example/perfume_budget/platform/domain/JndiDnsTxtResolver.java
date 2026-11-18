package com.example.perfume_budget.platform.domain;

import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Production TXT lookup through the JDK's built-in JNDI DNS provider ({@code jdk.naming.dns}): no new dependency.
 * Bounded by {@code tenant.custom-domain.dns-timeout-millis} and {@code dns-retries} per server (JNDI doubles the
 * timeout on each retry, so the worst case per server is {@code timeout * (2^(retries+1) - 1)}).
 */
@Component
public class JndiDnsTxtResolver implements DnsTxtResolver {

    private final List<String> servers;
    private final int timeoutMillis;
    private final int retries;

    @org.springframework.beans.factory.annotation.Autowired
    public JndiDnsTxtResolver(CustomDomainProperties props) {
        this(props.getDnsServers(), props.getDnsTimeoutMillis(), props.getDnsRetries());
    }

    JndiDnsTxtResolver(List<String> servers, int timeoutMillis, int retries) {
        this.servers = servers == null ? List.of() : servers.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.timeoutMillis = Math.max(100, timeoutMillis);
        this.retries = Math.max(0, Math.min(retries, 4));
    }

    @Override
    public List<String> lookupTxt(String fqdn) throws DnsLookupException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put(Context.PROVIDER_URL, servers.isEmpty() ? "dns:"
                : servers.stream().map(s -> "dns://" + s).collect(Collectors.joining(" ")));
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(timeoutMillis));
        env.put("com.sun.jndi.dns.timeout.retries", String.valueOf(retries));
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(fqdn, new String[]{"TXT"});
            Attribute txt = attrs.get("TXT");
            List<String> out = new ArrayList<>();
            if (txt != null) {
                NamingEnumeration<?> values = txt.getAll();
                while (values.hasMore()) {
                    Object v = values.next();
                    if (v != null) {
                        out.add(unquote(v.toString()));
                    }
                }
            }
            return out;
        } catch (NameNotFoundException e) {
            return List.of(); // NXDOMAIN: a definitive "no such record"
        } catch (NamingException | RuntimeException e) {
            throw new DnsLookupException("TXT lookup failed: " + e.getClass().getSimpleName(), null);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                    // nothing to release beyond the socket JNDI already closed
                }
            }
        }
    }

    /**
     * JNDI renders a TXT record as its character-strings separated by a blank, each one quoted (with {@code \"} and
     * {@code \\} escapes) when it contains a blank, a quote or a backslash: {@code "a b" c}. Join them the way DNS
     * clients do (no separator): blanks outside quotes are separators, never content.
     */
    static String unquote(String raw) {
        String s = raw.strip();
        StringBuilder out = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && inQuotes && i + 1 < s.length()) {
                out.append(s.charAt(++i));
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (inQuotes || !Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
