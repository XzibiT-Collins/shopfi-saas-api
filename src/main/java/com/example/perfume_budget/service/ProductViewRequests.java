package com.example.perfume_budget.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** VIEW-01: bot, crawler, library and prefetch policy of the view beacon (such a request is never counted). */
public final class ProductViewRequests {

    private static final int MAX_UA = 512;
    private static final Pattern AUTOMATED_UA = Pattern.compile(
            "bot|crawl|spider|slurp|headless|lighthouse|pingdom|uptime|monitor|curl/|wget|python|okhttp|go-http-client"
                    + "|java/|libwww|httpclient|node-fetch|axios|undici|postman|insomnia|scrapy|puppeteer|phantomjs"
                    + "|facebookexternalhit|embedly|whatsapp|preview|fetcher|scanner|archive");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,200}");

    private ProductViewRequests() {
    }

    /** True when the request must not count: missing/oversized/automated User-Agent or a prefetch/prerender request. */
    public static boolean isAutomated(String userAgent, String purpose, String secPurpose, String xMoz) {
        if (userAgent == null || userAgent.isBlank() || userAgent.length() > MAX_UA) {
            return true;
        }
        if (AUTOMATED_UA.matcher(userAgent.toLowerCase(Locale.ROOT)).find()) {
            return true;
        }
        return contains(purpose, "prefetch") || contains(secPurpose, "prefetch") || contains(secPurpose, "prerender")
                || contains(xMoz, "prefetch");
    }

    public static boolean isWellFormedSlug(String slug) {
        return slug != null && SLUG.matcher(slug).matches();
    }

    private static boolean contains(String header, String token) {
        return header != null && header.toLowerCase(Locale.ROOT).contains(token);
    }
}
