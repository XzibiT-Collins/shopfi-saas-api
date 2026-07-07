package com.example.perfume_budget.utils;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
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
        when(jwtUtil.generateAccessToken(anyString(), any(), anyLong(), anyString())).thenReturn("access");
        when(jwtUtil.generateRefreshToken(anyString(), any(), anyLong(), anyString())).thenReturn("refresh");

        authCookieUtil.setAuthCookies(null, response, user);

        verify(response, times(2)).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    void removeAuthCookies_Success() {
        authCookieUtil.removeAuthCookies(response);
        verify(response, times(2)).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    void refreshAccessToken_Success() {
        String refreshToken = "valid-refresh";
        User user = User.builder().id(1L).email("test@test.com").fullName("Test").isActive(true).roles(com.example.perfume_budget.enums.UserRole.CUSTOMER).build();
        
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateAccessToken(anyString(), any(), anyLong(), anyString())).thenReturn("new-access");

        authCookieUtil.refreshAccessToken(refreshToken, response);

        verify(jwtUtil).validateToken(refreshToken);
        verify(response).addHeader(eq("Set-Cookie"), contains("accessToken=new-access"));
    }

    @Test
    void refreshAccessToken_UserNotFound() {
        when(jwtUtil.extractUsername(anyString())).thenReturn("none@test.com");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authCookieUtil.refreshAccessToken("token", response));
    }
}
