package com.example.perfume_budget.utils;

import com.example.perfume_budget.model.User;
import com.example.perfume_budget.security.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthUserUtilTest {

    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;
    @Mock
    private AuthUser authUser;

    @InjectMocks
    private AuthUserUtil authUserUtil;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.setContext(securityContext);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUser_Success() {
        User user = User.builder().id(1L).build();
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(authUser);
        when(authUser.getUser()).thenReturn(user);

        User result = authUserUtil.getCurrentUser();

        assertEquals(user, result);
    }

    @Test
    void getCurrentUser_NullAuthentication() {
        when(securityContext.getAuthentication()).thenReturn(null);
        assertNull(authUserUtil.getCurrentUser());
    }

    @Test
    void getCurrentUser_NotAuthenticated() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(false);
        assertNull(authUserUtil.getCurrentUser());
    }

    @Test
    void getCurrentUser_WrongPrincipalType() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("not-an-auth-user");
        assertNull(authUserUtil.getCurrentUser());
    }
}
