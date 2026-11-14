package com.example.perfume_budget.theme;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Safety check for the {@code rich-text.body} Markdown subset (contract 7.1). Never renders: it parses with
 * CommonMark and inspects the AST. Link destinations are checked AFTER parsing, i.e. after entity decoding and
 * backslash-unescaping, so {@code &#106;avascript:} is seen as {@code javascript:}.
 *
 * <p>Allowed nodes: paragraph, text, bold, italic, h2/h3, one-level bullet/ordered lists, links (no title),
 * soft/hard breaks. Everything else (raw HTML, images, code, blockquotes, thematic breaks, reference
 * definitions, h1/h4+, nested lists) is rejected rather than stripped.
 */
final class RichTextChecker {

    static final int MAX_LINK_LENGTH = 512;
    private static final Parser PARSER = Parser.builder().build(); // core only: no extensions
    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile(".*[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}].*", Pattern.DOTALL);
    private static final Pattern PATH = Pattern.compile("^/(?!/)[^\\s<>\"'\\\\]*$");
    private static final Pattern HTTPS = Pattern.compile("^https://[^\\s<>\"'\\\\/][^\\s<>\"'\\\\]*$");
    private static final Pattern MAILTO = Pattern.compile("^mailto:[^\\s<>\"'\\\\]{3,}$");
    private static final Pattern TEL = Pattern.compile("^tel:\\+?[0-9().\\-]{3,30}$");
    private static final Pattern NUMERIC_REF = Pattern.compile("&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));?");
    private static final String[] BAD_SCHEMES = {"javascript:", "data:", "vbscript:", "file:", "blob:"};

    private RichTextChecker() {
    }

    /** Max nesting of list/indent levels (markers + indentation) on any line. */
    static final int MAX_NESTING = 3;

    /** A rejected body: stable field-error code and a short reason. */
    record Problem(String code, String message) {
    }

    /** Max list/blockquote lines in one body (contract 7.1 rule 4b); matches the frontend renderer budget. */
    static final int MAX_BLOCK_LINES = 60;

    /** Max body length in Unicode code points (the schema's {@code maxLength}). */
    static final int MAX_BODY_CODE_POINTS = 5000;

    /**
     * @return null when the body is safe, otherwise the problem. ALWAYS runs on every body, whatever its length:
     * the linear scans always execute, and only the CommonMark parse is skipped (after an explicit TOO_LONG
     * rejection) for a body over {@link #MAX_BODY_CODE_POINTS}, so oversize input is rejected, never skipped.
     */
    static Problem check(String body) {
        // Linear pre-scan BEFORE any parse: nested-marker bombs ("- - - - x" x1200) must never reach the parser
        // here or the Markdown renderer (contract 7.1).
        int[] scan = scan(body);
        if (scan[0] > MAX_NESTING) {
            return new Problem("RICH_TEXT_TOO_DEEP", "Markdown nesting is limited to " + MAX_NESTING + " levels");
        }
        if (scan[1] > MAX_BLOCK_LINES) {
            return new Problem("RICH_TEXT_TOO_MANY_LINES", "at most " + MAX_BLOCK_LINES + " list or quote lines are allowed per body");
        }
        if (body.codePointCount(0, body.length()) > MAX_BODY_CODE_POINTS) {
            return new Problem("TOO_LONG", "must be at most " + MAX_BODY_CODE_POINTS + " characters");
        }
        String reason = checkSafety(body);
        return reason == null ? null : new Problem("UNSAFE_CONTENT", reason);
    }

    /** Greatest per-line nesting: leading indentation (2 columns = 1 level, tab = 4 columns) plus list/quote markers. O(n). */
    static int maxNesting(String body) {
        return scan(body)[0];
    }

    /** Number of lines that start with a list or quote marker. O(n). */
    static int blockLines(String body) {
        return scan(body)[1];
    }

    /**
     * One linear pass. Lines end at LF, CR or CRLF (CommonMark line endings; a CR-only body must not hide a bomb).
     *
     * @return {max nesting (stops counting once over the cap), number of list/quote lines (stops once over the cap)}
     */
    private static int[] scan(String body) {
        int max = 0;
        int lines = 0;
        int n = body.length();
        int i = 0;
        while (i < n) {
            int col = 0;
            while (i < n && (body.charAt(i) == ' ' || body.charAt(i) == '\t')) {
                col += body.charAt(i) == '\t' ? 4 : 1;
                i++;
            }
            int depth = col / 2;
            boolean marked = false;
            // consecutive block markers: "- ", "* ", "+ ", "1. ", "1) ", "> "
            while (i < n) {
                char c = body.charAt(i);
                int j = i;
                if (c == '-' || c == '*' || c == '+' || c == '>') {
                    j = i + 1;
                } else if (c >= '0' && c <= '9') {
                    while (j < n && j - i < 10 && body.charAt(j) >= '0' && body.charAt(j) <= '9') {
                        j++;
                    }
                    if (j < n && (body.charAt(j) == '.' || body.charAt(j) == ')')) {
                        j++;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
                if (j < n && (body.charAt(j) == ' ' || body.charAt(j) == '\t')) {
                    depth++;
                    marked = true;
                    while (j < n && (body.charAt(j) == ' ' || body.charAt(j) == '\t')) {
                        j++;
                    }
                    i = j;
                } else if (c == '>') {
                    depth++;
                    marked = true;
                    i = j;
                } else {
                    if (j >= n || body.charAt(j) == '\n' || body.charAt(j) == '\r') {
                        marked = true; // an empty list item ("-" alone) is still a list line
                    }
                    break;
                }
            }
            if (marked) {
                lines++;
            }
            max = Math.max(max, depth);
            if (max > MAX_NESTING || lines > MAX_BLOCK_LINES) {
                return new int[]{max, lines};
            }
            while (i < n && body.charAt(i) != '\n' && body.charAt(i) != '\r') {
                i++;
            }
            if (i + 1 < n && body.charAt(i) == '\r' && body.charAt(i + 1) == '\n') {
                i++;
            }
            i++;
        }
        return new int[]{max, lines};
    }

    private static String checkSafety(String body) {
        if (body.indexOf('<') >= 0 || body.indexOf('>') >= 0) {
            return "angle brackets (raw HTML) are not allowed";
        }
        if (body.contains("![")) {
            return "images are not allowed";
        }
        if (suspiciousScheme(body)) {
            return "link uses a forbidden scheme";
        }
        Node doc = PARSER.parse(body);
        return walk(doc, 0);
    }

    private static String walk(Node node, int listDepth) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNext()) {
            String problem = visit(n, listDepth);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String visit(Node n, int listDepth) {
        if (n instanceof Paragraph || n instanceof StrongEmphasis || n instanceof Emphasis
                || n instanceof ListItem || n instanceof SoftLineBreak || n instanceof HardLineBreak) {
            return walk(n, listDepth);
        }
        if (n instanceof Text t) {
            return plain(t.getLiteral());
        }
        if (n instanceof Heading h) {
            if (h.getLevel() != 2 && h.getLevel() != 3) {
                return "only ## and ### headings are allowed";
            }
            return walk(n, listDepth);
        }
        if (n instanceof BulletList || n instanceof OrderedList) {
            if (listDepth >= 1) {
                return "nested lists are not allowed";
            }
            return walk(n, listDepth + 1);
        }
        if (n instanceof Link l) {
            if (l.getTitle() != null) {
                return "link titles are not allowed";
            }
            if (!allowedDestination(l.getDestination())) {
                return "link destination is not allowed";
            }
            return walk(n, listDepth);
        }
        return "unsupported Markdown element (" + n.getClass().getSimpleName() + ")";
    }

    private static String plain(String s) {
        if (s.indexOf('<') >= 0 || s.indexOf('>') >= 0) {
            return "angle brackets (raw HTML, including entity-encoded) are not allowed";
        }
        return null;
    }

    static boolean allowedDestination(String dest) {
        if (dest == null || dest.isEmpty() || dest.length() > MAX_LINK_LENGTH || CONTROL_OR_FORMAT.matcher(dest).matches()) {
            return false;
        }
        return PATH.matcher(dest).matches() || HTTPS.matcher(dest).matches()
                || MAILTO.matcher(dest).matches() || TEL.matcher(dest).matches();
    }

    /**
     * Defence in depth for obfuscated schemes that break link syntax and render as literal text (for example a
     * newline inside {@code javascript}): after every {@code ](} drop whitespace/controls, decode numeric entities
     * and look for a forbidden scheme.
     */
    private static boolean suspiciousScheme(String body) {
        int from = 0;
        while (true) {
            int i = body.indexOf("](", from);
            if (i < 0) {
                return false;
            }
            int end = Math.min(body.length(), i + 2 + 120);
            String tail = body.substring(i + 2, end);
            String cleaned = decodeNumeric(tail).replaceAll("[\\s\\p{Cc}\\p{Cf}]", "").toLowerCase(Locale.ROOT);
            for (String bad : BAD_SCHEMES) {
                if (cleaned.startsWith(bad)) {
                    return true;
                }
            }
            from = i + 2;
        }
    }

    private static String decodeNumeric(String s) {
        var m = NUMERIC_REF.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = m.group(1) != null ? Integer.parseInt(m.group(1), 16) : Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                cp = 0xFFFD;
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "�"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
