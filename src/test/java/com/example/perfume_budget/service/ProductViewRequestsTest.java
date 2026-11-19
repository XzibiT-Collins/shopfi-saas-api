package com.example.perfume_budget.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** VIEW-01: bot, library and prefetch policy of the view beacon. */
class ProductViewRequestsTest {

    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final String SAFARI_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0";

    @Test
    void realBrowsersCount() {
        for (String ua : new String[]{CHROME, SAFARI_IOS, FIREFOX}) {
            assertThat(ProductViewRequests.isAutomated(ua, null, null, null)).as(ua).isFalse();
        }
    }

    @Test
    void crawlersLibrariesAndHeadlessBrowsersDoNot() {
        for (String ua : new String[]{
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", "Bingbot/2.0", "AhrefsBot", "Slurp",
                "Mozilla/5.0 HeadlessChrome/126.0 Safari/537.36", "curl/8.5.0", "Wget/1.21", "python-requests/2.31",
                "okhttp/4.12.0", "Go-http-client/2.0", "Java/17.0.2", "Apache-HttpClient/4.5", "node-fetch/1.0", "axios/1.6",
                "facebookexternalhit/1.1", "WhatsApp/2.23", "Scrapy/2.11", "Lighthouse", "PostmanRuntime/7.37",
                "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X) (compatible; Google-InspectionTool/1.0)  Spider"}) {
            assertThat(ProductViewRequests.isAutomated(ua, null, null, null)).as(ua).isTrue();
        }
    }

    @Test
    void missingBlankOrOversizedUserAgentDoesNotCount() {
        assertThat(ProductViewRequests.isAutomated(null, null, null, null)).isTrue();
        assertThat(ProductViewRequests.isAutomated("  ", null, null, null)).isTrue();
        assertThat(ProductViewRequests.isAutomated("Mozilla/5.0 " + "x".repeat(600), null, null, null)).isTrue();
    }

    @Test
    void prefetchAndPrerenderDoNotCount() {
        assertThat(ProductViewRequests.isAutomated(CHROME, "prefetch", null, null)).isTrue();
        assertThat(ProductViewRequests.isAutomated(CHROME, null, "prefetch;prerender", null)).isTrue();
        assertThat(ProductViewRequests.isAutomated(CHROME, null, "prerender", null)).isTrue();
        assertThat(ProductViewRequests.isAutomated(CHROME, null, null, "Prefetch")).isTrue();
        assertThat(ProductViewRequests.isAutomated(CHROME, "", "", "")).isFalse();
    }

    @Test
    void slugShape() {
        assertThat(ProductViewRequests.isWellFormedSlug("oud-royal-1a2b3c4d")).isTrue();
        for (String bad : new String[]{null, "", "Upper", "a b", "a/b", "a_b", "é", "x".repeat(201), "a%00"}) {
            assertThat(ProductViewRequests.isWellFormedSlug(bad)).as(String.valueOf(bad)).isFalse();
        }
    }
}
