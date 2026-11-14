package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Unit level: exactly one BCrypt compare on every path (timing class), identical failure for every cause. */
class PlatformAuthServiceTest {

    PlatformAdminRepository repo = mock(PlatformAdminRepository.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    PlatformAuthProperties props = new PlatformAuthProperties();
    PlatformAuthService svc;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("DUMMY-HASH");
        svc = new PlatformAuthService(repo, encoder, new PlatformJwtService(PlatformJwtServiceTest.SECRET, props),
                new PlatformAuthCookies(props, true, "None"), new PlatformLoginRateLimiter(props));
    }

    private PlatformAdmin admin(boolean active) {
        return PlatformAdmin.builder().id(1L).email("root@x.test").password("REAL-HASH").fullName("Root").isActive(active).build();
    }

    private String loginFailureMessage(String email, String pw) {
        return catchThrowableOfType(BadCredentialsException.class,
                () -> svc.login(new PlatformLoginRequest(email, pw), "1.1.1.1", new MockHttpServletResponse())).getMessage();
    }

    private static <T extends Throwable> T catchThrowableOfType(Class<T> type, org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(type, c);
    }

    @Test
    void unknownEmail_stillComparesAgainstTheDummyHash_once() {
        when(repo.findByEmail("nobody@x.test")).thenReturn(Optional.empty());
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        assertThat(loginFailureMessage("nobody@x.test", "whatever-pass")).isEqualTo("Invalid email or password");
        verify(encoder, times(1)).matches("whatever-pass", "DUMMY-HASH");
    }

    @Test
    void wrongPassword_comparesOnce_sameMessage() {
        when(repo.findByEmail("root@x.test")).thenReturn(Optional.of(admin(true)));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        assertThat(loginFailureMessage("root@x.test", "wrong-pass")).isEqualTo("Invalid email or password");
        verify(encoder, times(1)).matches("wrong-pass", "REAL-HASH");
    }

    @Test
    void inactiveAdmin_withCorrectPassword_isRejectedGenerically_afterTheSameCompare() {
        when(repo.findByEmail("root@x.test")).thenReturn(Optional.of(admin(false)));
        when(encoder.matches("right-pass", "REAL-HASH")).thenReturn(true);
        assertThat(loginFailureMessage("root@x.test", "right-pass")).isEqualTo("Invalid email or password");
        verify(encoder, times(1)).matches("right-pass", "REAL-HASH");
    }

    @Test
    void emailIsNormalised() {
        when(repo.findByEmail("root@x.test")).thenReturn(Optional.of(admin(true)));
        when(encoder.matches("right-pass", "REAL-HASH")).thenReturn(true);
        var resp = new MockHttpServletResponse();
        assertThat(svc.login(new PlatformLoginRequest("  ROOT@X.test ", "right-pass"), "1.1.1.2", resp).role()).isEqualTo("SUPER_ADMIN");
        assertThat(resp.getHeaders("Set-Cookie")).hasSize(2);
    }

    @Test
    void refreshWithoutCookie_is401Style_andClearsCookies() {
        var resp = new MockHttpServletResponse();
        assertThatThrownBy(() -> svc.refresh(new org.springframework.mock.web.MockHttpServletRequest(), "1.1.1.3", resp))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(resp.getHeaders("Set-Cookie")).hasSize(2).allMatch(h -> h.contains("Max-Age=0"));
    }
}
