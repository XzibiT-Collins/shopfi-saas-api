package com.example.perfume_budget.utils;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthCookieUtilTest {

    @Mock
    private JWTUtil jwtUtil;
    @Mock
    private UserRepository userRepository;
    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private AuthCookieUtil authCookieUtil;

    @Test
    void setAuthCookies_Success() {
        User user = User.builder().id(1L).email("test@test.com").fullName("Test").roles(com.example.perfume_budget.enums.UserRole.CUSTOMER).build();
        when(jwtUtil.generateAccessToken(anyString(), any(), anyLong(), anyString(), anyInt())).thenReturn("access");
        when(jwtUtil.generateRefreshToken(anyString(), any(), anyLong(), anyString(), anyInt())).thenReturn("refresh");

        authCookieUtil.setAuthCookies(null, response, user);

        verify(response, times(2)).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    void removeAuthCookies_Success() {
        authCookieUtil.removeAuthCookies(response);
        verify(response, times(2)).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    void refreshAccessToken_refusesARefreshTokenMintedBeforeThePasswordChange() {
        String refreshToken = "stale-refresh";
        User user = User.builder().id(1L).email("test@test.com").fullName("Test").isActive(true).tokenVersion(1)
                .roles(com.example.perfume_budget.enums.UserRole.CUSTOMER).build();
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        when(jwtUtil.isRefreshToken(refreshToken)).thenReturn(true);
        when(jwtUtil.extractTenant(refreshToken)).thenReturn("acme");
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(jwtUtil.tokenVersionMatches(refreshToken, 1)).thenReturn(false);

        assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class,
                () -> authCookieUtil.refreshAccessToken(refreshToken, response));
        verify(response, never()).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    void refreshAccessToken_Success() {
        String refreshToken = "valid-refresh";
        User user = User.builder().id(1L).email("test@test.com").fullName("Test").isActive(true).roles(com.example.perfume_budget.enums.UserRole.CUSTOMER).build();
        
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        when(jwtUtil.isRefreshToken(refreshToken)).thenReturn(true);
        when(jwtUtil.extractTenant(refreshToken)).thenReturn("acme");
        when(jwtUtil.accessTokenTtlSeconds()).thenReturn(3600);
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("test@test.com");
        when(jwtUtil.tokenVersionMatches(anyString(), anyInt())).thenReturn(true);
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateAccessToken(anyString(), any(), anyLong(), anyString(), anyInt())).thenReturn("new-access");

        authCookieUtil.refreshAccessToken(refreshToken, response);

        verify(jwtUtil).validateToken(refreshToken);
        verify(response).addHeader(eq("Set-Cookie"), contains("accessToken=new-access"));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void refreshAccessToken_UserNotFound() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        when(jwtUtil.isRefreshToken(anyString())).thenReturn(true);
        when(jwtUtil.extractTenant(anyString())).thenReturn("acme");
        when(jwtUtil.extractUsername(anyString())).thenReturn("none@test.com");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authCookieUtil.refreshAccessToken("token", response));
    }

    // ---- AUTH-01 AC2: Max-Age derives from the JWT lifetimes; refresh is strictly tenant bound -----------------

    @Test
    void authCookies_maxAgeComesFromTheConfiguredJwtLifetimes_notHardCodedConstants() {
        User user = User.builder().id(1L).email("t@t.test").fullName("T").roles(com.example.perfume_budget.enums.UserRole.CUSTOMER).build();
        when(jwtUtil.generateAccessToken(anyString(), any(), anyLong(), anyString(), anyInt())).thenReturn("a");
        when(jwtUtil.generateRefreshToken(anyString(), any(), anyLong(), anyString(), anyInt())).thenReturn("r");
        when(jwtUtil.accessTokenTtlSeconds()).thenReturn(900);
        when(jwtUtil.refreshTokenTtlSeconds()).thenReturn(86400);

        authCookieUtil.setAuthCookies(null, response, user);

        verify(response).addHeader(eq("Set-Cookie"), argThat((String c) -> c.startsWith("accessToken=a") && c.contains("Max-Age=900")));
        verify(response).addHeader(eq("Set-Cookie"), argThat((String c) -> c.startsWith("refreshToken=r") && c.contains("Max-Age=86400")));
    }

    @Test
    void refreshAccessToken_tokenFromAnotherTenant_isSimplyInvalid_withAGenericMessage() {
        TenantContext.set(TenantIdentity.tenant("bravo", "tenant_bravo"));
        when(jwtUtil.isRefreshToken("t")).thenReturn(true);
        when(jwtUtil.extractTenant("t")).thenReturn("acme");

        var ex = assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class,
                () -> authCookieUtil.refreshAccessToken("t", response));
        assertEquals("Invalid or expired token", ex.getMessage());
        verifyNoInteractions(userRepository);
        verify(response, never()).addHeader(anyString(), anyString());
    }

    @Test
    void refreshAccessToken_platformClaimOrMissingTenantOrMissingContext_isRejected() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        when(jwtUtil.isRefreshToken(anyString())).thenReturn(true);
        when(jwtUtil.extractTenant("p")).thenReturn(TenantContext.PLATFORM_SLUG);
        when(jwtUtil.extractTenant("n")).thenReturn(null);
        assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class, () -> authCookieUtil.refreshAccessToken("p", response));
        assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class, () -> authCookieUtil.refreshAccessToken("n", response));
        TenantContext.clear();
        assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class, () -> authCookieUtil.refreshAccessToken("x", response));
        verifyNoInteractions(userRepository);
    }

    @Test
    void refreshAccessToken_missingOrBlankToken_is401Material() {
        assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class, () -> authCookieUtil.refreshAccessToken(null, response));
        assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class, () -> authCookieUtil.refreshAccessToken("  ", response));
        verifyNoInteractions(userRepository);
    }

    /** FIX-A2 (1): a token that is not a refresh token (an access token) is refused before any user lookup. */
    @Test
    void refreshAccessToken_aTokenThatIsNotARefreshToken_isInvalid_andNothingIsIssued() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        when(jwtUtil.isRefreshToken("access-token")).thenReturn(false);

        var ex = assertThrows(com.example.perfume_budget.exception.InvalidJWTTokenException.class,
                () -> authCookieUtil.refreshAccessToken("access-token", response));
        assertEquals("Invalid or expired token", ex.getMessage());
        verifyNoInteractions(userRepository);
        verify(response, never()).addHeader(anyString(), anyString());
    }
}
