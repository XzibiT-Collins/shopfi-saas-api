package com.example.perfume_budget.theme;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ThemeRequestSizeFilterTest {

    ThemeRequestSizeFilter filter = new ThemeRequestSizeFilter();
    FilterChain chain = mock(FilterChain.class);

    private MockHttpServletResponse run(String method, String uri, String contentType, long length) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setContentType(contentType);
        req.setContent(new byte[(int) length]); // MockHttpServletRequest derives Content-Length from the content
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void jsonAtLimitPasses_oneOverIs413_beforeChain() throws Exception {
        assertThat(run("PUT", "/api/v1/theme", "application/json", 64 * 1024).getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
        MockHttpServletResponse res = run("PUT", "/api/v1/theme", "application/json", 64 * 1024 + 1);
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(res.getContentAsString()).contains("64 KB").contains("\"data\":null");
        verify(chain, times(1)).doFilter(any(), any()); // not called again
    }

    @Test
    void multipartLimitIs12Mb() throws Exception {
        String ct = "multipart/form-data; boundary=x";
        assertThat(run("PUT", "/api/v1/theme", ct, 12L * 1024 * 1024).getStatus()).isEqualTo(200);
        assertThat(run("PUT", "/api/v1/theme", ct, 12L * 1024 * 1024 + 1).getStatus()).isEqualTo(413);
        assertThat(run("PUT", "/api/v1/theme", "MULTIPART/FORM-DATA; boundary=x", 13L * 1024 * 1024).getStatus()).isEqualTo(413);
    }

    @Test
    void doesNotTouchOtherPathsOrReads() throws Exception {
        assertThat(run("PUT", "/api/v1/tax/add-tax", "application/json", 10_000_000).getStatus()).isEqualTo(200);
        assertThat(run("GET", "/api/v1/theme", "application/json", 10_000_000).getStatus()).isEqualTo(200);
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void unknownLengthIsLeftToDownstreamBounds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/theme");
        req.setContentType("application/json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }
}
