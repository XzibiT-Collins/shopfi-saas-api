package com.example.perfume_budget.platform.domain;

import java.net.IDN;
import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalisation and validation of a tenant's custom domain (DOMAIN-02 AC1). Allow-list style, like
 * {@code TenantSlugs}: a value is either returned in its one canonical form or rejected with a stable code; it is never
 * "repaired" beyond trimming, lower-casing, removing ONE trailing dot and converting an internationalised name to its
 * ASCII (punycode) form.
 *
 * <p>Rules, checked in this order (the first failing rule decides the code):
 * <ol>
 *   <li>{@link #REQUIRED}: null or blank.</li>
 *   <li>{@link #IP_LITERAL}: an IPv6 literal (with or without brackets) or an IPv4 literal in any notation the
 *       resolver libraries accept (dotted, a single decimal/hex/octal number, short forms such as {@code 127.1}).</li>
 *   <li>{@link #INVALID}: not a syntactically valid FQDN: scheme, path, port, userinfo, wildcard, underscore or other
 *       characters outside {@code [a-z0-9.-]} after IDN conversion; an empty label, a label over 63 or a name over 253
 *       characters; a label starting/ending with a hyphen; a non-IDN label with hyphens in positions 3-4; an
 *       {@code xn--} label that is not valid punycode; fewer than two labels; a top-level label that does not start
 *       with a letter.</li>
 *   <li>{@link #PLATFORM}: equal to, under, or a parent of {@code tenant.base-domain}, one of
 *       {@code tenant.platform-domains}, or a host of the static CORS lists (platform-owned names).</li>
 *   <li>{@link #CONFUSABLE}: visually confusable with one of those platform names (Unicode NFKD, combining marks dropped,
 *       Cyrillic/Greek/Armenian look-alikes and {@code 0/1/rn/vv} mapped to Latin), e.g. {@code xn--shpfi-...} with a
 *       Cyrillic "o".</li>
 *   <li>{@link #RESERVED}: {@code localhost} or a reserved / internal name or suffix (RFC 2606/6761/7686, {@code .internal},
 *       {@code .local}, {@code .lan}, {@code .corp}, {@code .home.arpa}, {@code example.com}, ...).</li>
 * </ol>
 */
public final class CustomDomains {

    public static final String REQUIRED = "DOMAIN_REQUIRED";
    public static final String INVALID = "DOMAIN_INVALID";
    public static final String IP_LITERAL = "DOMAIN_IP_LITERAL";
    public static final String PLATFORM = "DOMAIN_PLATFORM";
    public static final String CONFUSABLE = "DOMAIN_CONFUSABLE";
    public static final String RESERVED = "DOMAIN_RESERVED";

    public static final int MAX_LENGTH = 253;
    private static final int MAX_RAW_LENGTH = 300;

    /** Names (and everything under them) that can never be a tenant's public storefront. */
    static final Set<String> RESERVED_SUFFIXES = Set.of(
            "localhost", "localdomain", "local", "internal", "intranet", "lan", "home", "corp", "private",
            "test", "example", "invalid", "onion", "arpa", "alt", "home.arpa",
            "example.com", "example.net", "example.org");

    private static final Pattern ASCII_HOST = Pattern.compile("^[a-z0-9.-]+$");
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    /** One IPv4 component as inet_aton() accepts it: decimal, octal (leading 0) or hex (0x). */
    private static final Pattern IPV4_PART = Pattern.compile("^(?:0x[0-9a-f]*|[0-9]+)$");
    private static final Pattern IPV6 = Pattern.compile("^\\[?[0-9a-f:.]*:[0-9a-f:.]*(?:%[a-z0-9]+)?\\]?$");

    /** The platform-owned names a custom domain must stay away from, plus any extra reserved suffixes. */
    public record Policy(Set<String> platformNames, Set<String> extraReserved) {

        public Policy {
            platformNames = clean(platformNames);
            extraReserved = clean(extraReserved);
        }

        /**
         * @param baseDomain      {@code tenant.base-domain} (may be blank)
         * @param platformDomains {@code tenant.platform-domains} (IP literals among them are ignored)
         * @param otherHosts      hosts of {@code cors.allowed-origins} / {@code platform.cors.allowed-origins}
         * @param extraReserved   {@code tenant.custom-domain.reserved-suffixes}
         */
        public static Policy of(String baseDomain, Collection<String> platformDomains, Collection<String> otherHosts,
                                Collection<String> extraReserved) {
            Set<String> names = new LinkedHashSet<>();
            if (baseDomain != null) {
                names.add(baseDomain);
            }
            if (platformDomains != null) {
                names.addAll(platformDomains);
            }
            if (otherHosts != null) {
                names.addAll(otherHosts);
            }
            return new Policy(names, extraReserved == null ? Set.of() : Set.copyOf(extraReserved));
        }

        private static Set<String> clean(Set<String> in) {
            Set<String> out = new LinkedHashSet<>();
            if (in != null) {
                for (String s : in) {
                    if (s == null) {
                        continue;
                    }
                    String v = s.strip().toLowerCase(Locale.ROOT);
                    while (v.endsWith(".")) {
                        v = v.substring(0, v.length() - 1);
                    }
                    if (!v.isEmpty() && !isIpLiteral(v)) {
                        out.add(v);
                    }
                }
            }
            return Set.copyOf(out);
        }
    }

    private CustomDomains() {
    }

    /**
     * @return the canonical form (lower-case ASCII, punycode for IDN labels, no trailing dot)
     * @throws CustomDomainRejectedException with one of the codes above
     */
    public static String normalize(String raw, Policy policy) {
        if (raw == null || raw.isBlank()) {
            throw reject(REQUIRED, "A domain is required.");
        }
        if (raw.length() > MAX_RAW_LENGTH) {
            throw reject(INVALID, "The domain is not a valid host name.");
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (isIpLiteral(s) || isIpLiteral(stripOneDot(s))) {
            throw reject(IP_LITERAL, "An IP address cannot be used as a custom domain.");
        }
        if (!isAscii(s)) {
            try {
                s = IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw reject(INVALID, "The domain is not a valid host name.");
            }
        }
        s = stripOneDot(s);
        if (!ASCII_HOST.matcher(s).matches()) {
            throw reject(INVALID, "The domain is not a valid host name.");
        }
        if (isIpLiteral(s)) {
            throw reject(IP_LITERAL, "An IP address cannot be used as a custom domain.");
        }
        checkFqdn(s);
        String unicode = toUnicode(s);
        for (String p : policy.platformNames()) {
            if (s.equals(p) || s.endsWith("." + p) || p.endsWith("." + s)) {
                throw reject(PLATFORM, "This domain belongs to the platform and cannot be used as a custom domain.");
            }
        }
        String skeleton = skeleton(unicode);
        for (String p : policy.platformNames()) {
            String ps = skeleton(toUnicode(p));
            if (skeleton.equals(ps) || skeleton.endsWith("." + ps)) {
                throw reject(CONFUSABLE, "This domain looks like a platform domain and cannot be used.");
            }
        }
        for (String r : RESERVED_SUFFIXES) {
            if (s.equals(r) || s.endsWith("." + r)) {
                throw reject(RESERVED, "This domain is reserved and cannot be used as a custom domain.");
            }
        }
        for (String r : policy.extraReserved()) {
            if (s.equals(r) || s.endsWith("." + r)) {
                throw reject(RESERVED, "This domain is reserved and cannot be used as a custom domain.");
            }
        }
        return s;
    }

    private static void checkFqdn(String s) {
        if (s.length() > MAX_LENGTH) {
            throw reject(INVALID, "The domain is not a valid host name.");
        }
        String[] labels = s.split("\\.", -1);
        if (labels.length < 2) {
            // a single label is never a public FQDN; "localhost" is reported as reserved
            throw reject(RESERVED_SUFFIXES.contains(s) ? RESERVED : INVALID, RESERVED_SUFFIXES.contains(s)
                    ? "This domain is reserved and cannot be used as a custom domain."
                    : "The domain must be a fully qualified host name (for example www.your-store.com).");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw reject(INVALID, "The domain is not a valid host name.");
            }
            if (label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-') {
                if (!label.startsWith("xn--") || !validPunycode(label)) {
                    throw reject(INVALID, "The domain is not a valid host name.");
                }
            }
        }
        String tld = labels[labels.length - 1];
        if (!Character.isLetter(tld.charAt(0)) && !tld.startsWith("xn--")) {
            throw reject(INVALID, "The domain is not a valid host name.");
        }
    }

    private static boolean validPunycode(String label) {
        String decoded = IDN.toUnicode(label, IDN.USE_STD3_ASCII_RULES);
        if (decoded.equals(label) || isAscii(decoded)) {
            return false; // not decodable, or an ASCII-only "IDN" (xn--abc-) that only disguises a plain name
        }
        try {
            return IDN.toASCII(decoded, IDN.USE_STD3_ASCII_RULES).equalsIgnoreCase(label);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True for IPv6 literals and every IPv4 spelling inet_aton() accepts (1 to 4 numeric parts). */
    static boolean isIpLiteral(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.indexOf(':') >= 0 || s.startsWith("[")) {
            return IPV6.matcher(s).matches();
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length > 4) {
            return false;
        }
        for (String p : parts) {
            if (!IPV4_PART.matcher(p).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String stripOneDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static String toUnicode(String ascii) {
        StringBuilder out = new StringBuilder();
        for (String label : ascii.split("\\.", -1)) {
            if (out.length() > 0) {
                out.append('.');
            }
            out.append(label.startsWith("xn--") ? IDN.toUnicode(label, IDN.USE_STD3_ASCII_RULES) : label);
        }
        return out.toString();
    }

    /** Look-alike characters folded to the Latin letter they imitate (lower case only: names are lower-cased). */
    private static final Map<Character, String> CONFUSABLES = Map.ofEntries(
            // Cyrillic
            Map.entry('а', "a"), Map.entry('в', "b"), Map.entry('с', "c"), Map.entry('ԁ', "d"), Map.entry('е', "e"),
            Map.entry('һ', "h"), Map.entry('і', "i"), Map.entry('ј', "j"), Map.entry('к', "k"), Map.entry('ӏ', "l"),
            Map.entry('м', "m"), Map.entry('п', "n"), Map.entry('о', "o"), Map.entry('р', "p"), Map.entry('ԛ', "q"),
            Map.entry('г', "r"), Map.entry('ѕ', "s"), Map.entry('т', "t"), Map.entry('ц', "u"), Map.entry('ѵ', "v"),
            Map.entry('ԝ', "w"), Map.entry('х', "x"), Map.entry('у', "y"), Map.entry('ү', "y"), Map.entry('ʒ', "z"),
            // Greek
            Map.entry('α', "a"), Map.entry('β', "b"), Map.entry('ϲ', "c"), Map.entry('ε', "e"), Map.entry('η', "n"),
            Map.entry('ι', "i"), Map.entry('κ', "k"), Map.entry('ν', "v"), Map.entry('ο', "o"), Map.entry('ρ', "p"),
            Map.entry('τ', "t"), Map.entry('υ', "u"), Map.entry('χ', "x"), Map.entry('ω', "w"), Map.entry('γ', "y"),
            // Armenian and Latin extensions
            Map.entry('օ', "o"), Map.entry('ս', "u"), Map.entry('հ', "h"), Map.entry('ո', "n"), Map.entry('զ', "q"),
            Map.entry('ı', "i"), Map.entry('ɑ', "a"), Map.entry('ɡ', "g"), Map.entry('ℓ', "l"), Map.entry('ɩ', "i"),
            Map.entry('ß', "ss"),
            // digits that pass for letters
            Map.entry('0', "o"), Map.entry('1', "l"));

    /** A comparison key: two names with the same skeleton look alike. */
    static String skeleton(String unicodeName) {
        String n = Normalizer.normalize(unicodeName.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            String mapped = CONFUSABLES.get(c);
            out.append(mapped != null ? mapped : String.valueOf(c));
        }
        return out.toString().replace("rn", "m").replace("vv", "w");
    }

    /** The codes this class can produce (for documentation tests). */
    public static List<String> codes() {
        return List.of(REQUIRED, INVALID, IP_LITERAL, PLATFORM, CONFUSABLE, RESERVED);
    }

    private static CustomDomainRejectedException reject(String code, String message) {
        return new CustomDomainRejectedException(code, message);
    }
}
