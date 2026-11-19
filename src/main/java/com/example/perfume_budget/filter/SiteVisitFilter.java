package com.example.perfume_budget.filter;

import com.example.perfume_budget.model.SiteVisit;
import com.example.perfume_budget.repository.SiteVisitRepository;
import com.example.perfume_budget.security.ClientIps;
import com.example.perfume_budget.security.cors.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class SiteVisitFilter extends OncePerRequestFilter {
    private final SiteVisitRepository siteVisitRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String page = redactSecretLikeSegments(request.getRequestURI());
        String ip = resolveIpAddress(request);
        LocalDate today = LocalDate.now();

        if (!siteVisitRepository.existsByIpAddressAndVisitDateAndPage(ip, today,page)) {
            siteVisitRepository.save(SiteVisit.builder()
                    .ipAddress(ip)
                    .visitDate(today)
                    .page(page)
                    .build());
        }

        filterChain.doFilter(request, response);
    }

    /** Container-resolved client address (trusted-proxy aware); never a raw client supplied header. */
    private String resolveIpAddress(HttpServletRequest request) {
        return ClientIps.clientIp(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // the decoded, canonical path (DOMAIN-01b S1): the raw URI would let %70-style encodings slip past the tests
        String path = canonical(request);
        // HEAD is a probe/prefetch, not a visit (SEC-03b: HEAD became public on the storefront reads)
        return "HEAD".equalsIgnoreCase(request.getMethod()) ||
                isViewBeacon(request, path) ||
                hasInvalidPercentEncoding(request.getRequestURI()) ||
                isWebhookPath(request) ||
                resemblesWebhook(request.getRequestURI()) ||
                path.startsWith("/actuator") ||
                path.startsWith("/api/v1/auth/me")||
                path.endsWith(".js") ||
                path.endsWith(".css") ||
                path.endsWith(".ico");
    }

    /**
     * VIEW-01: the product view beacon is not a page visit and its path carries a caller chosen slug: recording it would
     * store the address and an arbitrary string per request (an unbounded-row, PII path). It has its own bounded,
     * in-memory dedupe and rate limit instead.
     */
    static boolean isViewBeacon(HttpServletRequest request, String canonicalPath) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && canonicalPath.matches("/api/v1/product/[^/]+/view/?");
    }

    /**
     * API-04: {@code /api/v1/webhook/{token}} carries the tenant's secret webhook token in the path, so it is never
     * stored as a "page" (nor is it a visit). Rework 2: decided on {@link RequestPaths#canonical} (percent-decoded,
     * {@code ;} content and duplicate slashes removed, lower-cased), and conservatively also on the raw URI and on
     * further decodings of the path, so no spelling ({@code %77ebhook}, {@code %2577ebhook}, {@code //},
     * {@code ;params}, upper case) gets the token into {@code site_visits}.
     */
    static boolean isWebhookPath(HttpServletRequest request) {
        if (isWebhookPath(canonical(request))) {
            return true;
        }
        return isWebhookPath(request.getRequestURI());
    }

    /** Canonical path; a malformed percent-encoding (not decodable) falls back to the lower-cased raw URI. */
    private static String canonical(HttpServletRequest request) {
        try {
            return RequestPaths.canonical(request);
        } catch (IllegalArgumentException malformed) {
            String raw = request.getRequestURI();
            return raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final int MAX_DECODE_ROUNDS = 3;

    /**
     * EVT-01 (API-04 review Q6): a path whose percent-encoding is not strictly valid, in any of the first
     * {@link #MAX_DECODE_ROUNDS} decoding rounds, is unrecordable. Invalid means a malformed escape ({@code %zz},
     * {@code %4}, a trailing {@code %}) or bytes that are not well-formed UTF-8: overlong forms ({@code %C1%A2} for
     * {@code b}, {@code %C0%AF} for {@code /}), surrogates, stray continuation / {@code %FF} bytes, truncated sequences.
     * The lenient decoder used for routing turns such bytes into U+FFFD, so {@code /api/v1/we%C1%A2hook/<token>} no
     * longer looked like the webhook and its token was stored; no legitimate storefront page needs such a spelling.
     */
    static boolean hasInvalidPercentEncoding(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String p = rawPath;
        for (int round = 0; round < MAX_DECODE_ROUNDS && p.indexOf('%') >= 0; round++) {
            p = strictDecode(p);
            if (p == null) {
                return true;
            }
        }
        return false;
    }

    /** Percent-decodes {@code s} as strict UTF-8; {@code null} when an escape is malformed or the bytes are not UTF-8. */
    private static String strictDecode(String s) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write((hi << 4) | lo);
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                byte[] enc = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(enc, 0, enc.length);
                i += Character.charCount(cp);
            }
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            return null;
        }
    }

    static boolean isWebhookPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path;
        for (int round = 0; ; round++) {
            String n = p.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/").toLowerCase(java.util.Locale.ROOT);
            if (n.equals("/api/v1/webhook") || n.startsWith("/api/v1/webhook/")) {
                return true;
            }
            if (round == MAX_DECODE_ROUNDS || !p.contains("%")) {
                return false;
            }
            try {
                p = org.springframework.web.util.UriUtils.decode(p, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }
    }

    // ---- EVT-01 rework 1 (F2)

    /**
     * True when ANY spelling of the path, raw or after up to {@link #MAX_DECODE_ROUNDS} lenient decodings, has a path
     * SEGMENT that starts with "webhook" once reduced to its letters and digits: Unicode compatibility forms are folded
     * (NFKD: full-width {@code ｗ}, mathematical letters, accents), common Cyrillic / Greek / small-capital look-alikes
     * are mapped to Latin, and everything that is not a letter or digit is dropped (zero-width and other invisible
     * characters, punctuation such as {@code -}, {@code _}, {@code .}, {@code ;}). Such a path is never recorded: the
     * router only serves the exact {@code /api/v1/webhook/{token}}, so these are mistyped or obfuscated webhook URLs
     * whose next segment is likely the tenant's secret token.
     *
     * <p>EVT-01b L4: per segment (split on {@code /} after each decoding, so an encoded {@code %2F} still starts a
     * segment), and only at the START of a segment: {@code cobweb-hookah} or {@code cobweb/hookah} spell "webhook"
     * only across a word or segment boundary and are counted again. Whatever is not caught here is still covered by
     * the token redaction ({@link #redactSecretLikeSegments}).
     */
    static boolean resemblesWebhook(String rawPath) {
        for (String form : decodedForms(rawPath)) {
            for (String segment : form.split("[/\\\\]")) {
                if (skeleton(segment).startsWith("webhook")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The raw path and its lenient decodings (malformed escapes stop the decoding, invalid UTF-8 becomes U+FFFD). */
    private static java.util.List<String> decodedForms(String rawPath) {
        java.util.List<String> forms = new java.util.ArrayList<>();
        if (rawPath == null) {
            return forms;
        }
        String p = rawPath;
        forms.add(p);
        for (int round = 0; round < MAX_DECODE_ROUNDS && p.indexOf('%') >= 0; round++) {
            try {
                p = org.springframework.web.util.UriUtils.decode(p, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                break;
            }
            forms.add(p);
        }
        return forms;
    }

    /** Letters and digits only, compatibility-folded, accent-free, look-alikes mapped to Latin, lower case. */
    static String skeleton(String s) {
        String folded = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).toLowerCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder(folded.length());
        folded.codePoints().forEach(cp -> {
            int latin = LOOK_ALIKES.getOrDefault(cp, cp);
            if (Character.isLetterOrDigit(latin)) {
                out.appendCodePoint(latin);
            }
        });
        return out.toString();
    }

    private static final java.util.Map<Integer, Integer> LOOK_ALIKES = lookAlikes();

    private static java.util.Map<Integer, Integer> lookAlikes() {
        java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
        // Cyrillic
        m.put(0x0430, (int) 'a'); m.put(0x0435, (int) 'e'); m.put(0x0450, (int) 'e'); m.put(0x0451, (int) 'e');
        m.put(0x043E, (int) 'o'); m.put(0x0440, (int) 'p'); m.put(0x0441, (int) 'c'); m.put(0x0443, (int) 'y');
        m.put(0x0445, (int) 'x'); m.put(0x043A, (int) 'k'); m.put(0x049B, (int) 'k'); m.put(0x04BB, (int) 'h');
        m.put(0x043D, (int) 'h'); m.put(0x051D, (int) 'w'); m.put(0x0461, (int) 'w'); m.put(0x044C, (int) 'b');
        m.put(0x0432, (int) 'b'); m.put(0x0455, (int) 's'); m.put(0x0456, (int) 'i');
        // Greek
        m.put(0x03BF, (int) 'o'); m.put(0x03BA, (int) 'k'); m.put(0x03B5, (int) 'e'); m.put(0x03B2, (int) 'b');
        m.put(0x03C9, (int) 'w'); m.put(0x03B7, (int) 'h'); m.put(0x03BD, (int) 'v');
        // Latin small capitals and other look-alikes NFKD leaves alone
        m.put(0x1D21, (int) 'w'); m.put(0x1D07, (int) 'e'); m.put(0x0299, (int) 'b'); m.put(0x029C, (int) 'h');
        m.put(0x1D0F, (int) 'o'); m.put(0x1D0B, (int) 'k'); m.put(0x0138, (int) 'k'); m.put(0x0131, (int) 'i');
        return java.util.Map.copyOf(m);
    }

    private static final java.util.regex.Pattern SECRET_LIKE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{32,}");

    /**
     * Second layer, independent of how the webhook route was spelled: a path segment that decodes to something shaped
     * like a generated secret (a run of 32+ base64url characters mixing upper and lower case, e.g. the 43-character
     * webhook token: 32 random bytes) is stored as {@code [redacted]}. Storefront slugs are lower case and are kept.
     */
    static String redactSecretLikeSegments(String rawPath) {
        if (rawPath == null || rawPath.length() < 32) {
            return rawPath;
        }
        String[] segments = rawPath.split("/", -1);
        boolean changed = false;
        for (int i = 0; i < segments.length; i++) {
            for (String form : decodedForms(segments[i])) {
                if (containsSecretLike(form)) {
                    segments[i] = "[redacted]";
                    changed = true;
                    break;
                }
            }
        }
        return changed ? String.join("/", segments) : rawPath;
    }

    private static boolean containsSecretLike(String s) {
        java.util.regex.Matcher m = SECRET_LIKE.matcher(s);
        while (m.find()) {
            String run = m.group();
            if (run.chars().anyMatch(Character::isUpperCase) && run.chars().anyMatch(Character::isLowerCase)) {
                return true;
            }
        }
        return false;
    }
}
