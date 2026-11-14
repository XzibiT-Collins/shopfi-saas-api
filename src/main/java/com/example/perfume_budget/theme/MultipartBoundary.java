package com.example.perfume_budget.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the {@code boundary} parameter of a {@code multipart/form-data} Content-Type per RFC 2046 section 5.1.1:
 * 1 to 70 characters of {@code DIGIT / ALPHA / ' ( ) + _ , - . / : = ?} (a space is allowed inside but not at the end,
 * so it needs a quoted value). Parameter names are case-insensitive. Only a boundary that is PRESENT is judged: an
 * absent one is left to the container's parser (see ThemeWriteGuardFilter). More than one {@code boundary}
 * parameter is rejected as ambiguous (parsers disagree about which one wins). Unquoted commas are rejected because
 * Tomcat's parameter parser also splits on ',' and would see a shorter boundary than we validated.
 */
final class MultipartBoundary {

    private static final String SPECIAL = "'()+_,-./:=?";

    private MultipartBoundary() {
    }

    /** True when the header carries a {@code boundary} parameter that is not acceptable. */
    static boolean isInvalid(String contentType) {
        if (contentType == null) {
            return false;
        }
        List<String[]> params = parameters(contentType);
        int found = 0;
        for (String[] p : params) {
            if (p[0].equalsIgnoreCase("boundary")) {
                found++;
            }
        }
        if (found == 0) {
            return false;
        }
        if (found > 1) {
            return true;
        }
        for (String[] p : params) {
            if (p[0].equalsIgnoreCase("boundary")) {
                return !validValue(p[1], p[2].equals("q"));
            }
        }
        return false;
    }

    private static boolean validValue(String v, boolean quoted) {
        if (v.isEmpty() || v.length() > 70 || v.endsWith(" ")) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || SPECIAL.indexOf(c) >= 0 || (c == ' ' && quoted);
            if (!ok || (c == ',' && !quoted)) {
                return false;
            }
        }
        return true;
    }

    /** Parameters after the media type as {name, value, "q"|"u"} (quoted or unquoted), split on ';' outside quotes. */
    private static List<String[]> parameters(String ct) {
        List<String[]> out = new ArrayList<>();
        int i = ct.indexOf(';');
        while (i >= 0 && i < ct.length()) {
            i++; // past ';'
            StringBuilder name = new StringBuilder();
            while (i < ct.length() && ct.charAt(i) != '=' && ct.charAt(i) != ';') {
                name.append(ct.charAt(i++));
            }
            if (i >= ct.length() || ct.charAt(i) == ';') {
                // a parameter without '=': ignore it
                if (i >= ct.length()) {
                    break;
                }
                continue;
            }
            i++; // past '='
            while (i < ct.length() && (ct.charAt(i) == ' ' || ct.charAt(i) == '\t')) {
                i++;
            }
            StringBuilder val = new StringBuilder();
            boolean quoted = i < ct.length() && ct.charAt(i) == '"';
            if (quoted) {
                i++;
                while (i < ct.length() && ct.charAt(i) != '"') {
                    if (ct.charAt(i) == '\\' && i + 1 < ct.length()) {
                        i++;
                    }
                    val.append(ct.charAt(i++));
                }
                i++; // closing quote (or end)
                while (i < ct.length() && ct.charAt(i) != ';') {
                    i++; // junk after the closing quote is ignored
                }
            } else {
                while (i < ct.length() && ct.charAt(i) != ';') {
                    val.append(ct.charAt(i++));
                }
            }
            String v = quoted ? val.toString() : val.toString().trim();
            out.add(new String[]{name.toString().trim(), v, quoted ? "q" : "u"});
            if (i >= ct.length()) {
                break;
            }
        }
        return out;
    }
}
