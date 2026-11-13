package com.example.perfume_budget.theme;

import com.example.perfume_budget.controller.StoreThemeController;
import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** THEME-04c unit tests on the controller/handler response itself, so no other filter can mask a removed header. */
class StoreThemeCacheHeadersTest {

    private final ThemeService service = mock(ThemeService.class);
    private final StoreThemeController controller = new StoreThemeController(service);

    private ResponseEntity<?> get(MockHttpServletRequest req, String ifNoneMatch) {
        when(service.getActiveTheme()).thenReturn(new ThemeSnapshot("{\"revision\":0}", 1));
        return controller.getTheme(req, ifNoneMatch);
    }

    @Test
    void varyNamesBothOriginAndReferer_onTheControllerResponse_200And304() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/theme");
        req.addHeader("Origin", "https://a.shopfi.test");
        for (ResponseEntity<?> r : new ResponseEntity<?>[]{get(req, null), get(req, "W/\"1\"")}) {
            assertThat(String.join(",", r.getHeaders().getValuesAsList("Vary"))).contains("Origin").contains("Referer");
        }
        assertThat(get(req, "W/\"1\"").getStatusCode().value()).isEqualTo(304);
    }

    @Test
    void originPresent_isPubliclyCacheable() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/theme");
        req.addHeader("Origin", "https://a.shopfi.test");
        assertThat(get(req, null).getHeaders().getFirst("Cache-Control"))
                .isEqualTo("public, max-age=60, s-maxage=300, stale-while-revalidate=600");
    }

    @Test
    void noOrigin_orRefererFallback_orUnusableOrigin_isPrivateNoStore_including304() {
        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/api/v1/theme");
        MockHttpServletRequest refererOnly = new MockHttpServletRequest("GET", "/api/v1/theme");
        refererOnly.addHeader("Referer", "https://a.shopfi.test/page");
        MockHttpServletRequest nullOrigin = new MockHttpServletRequest("GET", "/api/v1/theme");
        nullOrigin.addHeader("Origin", "null"); // sandboxed iframe: no host, the resolver falls back to Referer
        nullOrigin.addHeader("Referer", "https://a.shopfi.test/page");
        for (MockHttpServletRequest req : new MockHttpServletRequest[]{none, refererOnly, nullOrigin}) {
            for (String inm : new String[]{null, "W/\"1\""}) {
                ResponseEntity<?> r = get(req, inm);
                assertThat(r.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
                assertThat(r.getHeaders().getFirst("Cache-Control")).doesNotContain("public").doesNotContain("s-maxage");
            }
        }
    }

    @Test
    void maxUploadMessage_namesTheRealLimit_not10MB() {
        ResponseEntity<?> r = new GlobalExceptionHandler()
                .handleMaxSizeException(new MaxUploadSizeExceededException(12L * 1024 * 1024));
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(String.valueOf(r.getBody())).contains("12MB").doesNotContain("10MB");
    }
}
