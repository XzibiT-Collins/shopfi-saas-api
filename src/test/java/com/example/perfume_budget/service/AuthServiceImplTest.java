package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.auth.request.LoginRequest;
import com.example.perfume_budget.dto.auth.request.LoginOtpResendRequest;
import com.example.perfume_budget.dto.auth.request.LoginOtpVerificationRequest;
import com.example.perfume_budget.dto.auth.request.OtpVerificationRequest;
import com.example.perfume_budget.dto.auth.request.RegistrationRequest;
import com.example.perfume_budget.dto.auth.request.ResetPasswordRequest;
import com.example.perfume_budget.dto.auth.response.AuthResponse;
import com.example.perfume_budget.dto.auth.response.LoginResponse;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.InactiveAccountException;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.security.AuthUser;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.FeatureFlagService;
import com.example.perfume_budget.service.interfaces.OtpService;
import com.example.perfume_budget.utils.AuthCookieUtil;
import com.example.perfume_budget.utils.AuthUserUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private AuthCookieUtil authCookieUtil;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthUserUtil authUserUtil;
    @Mock
    private OtpService otpService;
    @Mock
    private FeatureFlagService featureFlagService;
    @Mock
    private FrontDeskAccessService frontDeskAccessService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private User testUser;
    private LoginRequest loginRequest;
    private RegistrationRequest registrationRequest;
    private OtpVerificationRequest otpRequest;
    private LoginOtpVerificationRequest loginOtpVerificationRequest;
    private LoginOtpResendRequest loginOtpResendRequest;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .fullName("Test User")
                .password("encodedPassword")
                .isActive(true)
                .emailVerified(true)
                .roles(UserRole.CUSTOMER)
                .build();

        loginRequest = new LoginRequest("test@example.com", "password");
        registrationRequest = new RegistrationRequest("test@example.com", "Test User", "password", "password");
        otpRequest = new OtpVerificationRequest("test@example.com", "123456");
        loginOtpVerificationRequest = new LoginOtpVerificationRequest("challenge-123", "123456");
        loginOtpResendRequest = new LoginOtpResendRequest("challenge-123");
    }

    @Test
    void login_Success() {
        Authentication authentication = mock(Authentication.class);
        AuthUser authUser = new AuthUser(testUser);
        when(authentication.getPrincipal()).thenReturn(authUser);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(featureFlagService.isEnabled(FeatureFlagKey.LOGIN_OTP, testUser.getRoles())).thenReturn(false);
        when(frontDeskAccessService.getEffectivePermissions(testUser)).thenReturn(Set.of());

        LoginResponse result = authService.login(loginRequest, response);

        assertNotNull(result);
        assertFalse(result.requiresOtp());
        assertNotNull(result.auth());
        assertEquals(testUser.getEmail(), result.auth().email());
        assertEquals(testUser.getFullName(), result.auth().fullName());
        verify(authCookieUtil).setAuthCookies(null, response, testUser);
    }

    @Test
    void login_Success_WhenLoginOtpEnabled_ReturnsChallenge() {
        Authentication authentication = mock(Authentication.class);
        AuthUser authUser = new AuthUser(testUser);
        when(authentication.getPrincipal()).thenReturn(authUser);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(featureFlagService.isEnabled(FeatureFlagKey.LOGIN_OTP, testUser.getRoles())).thenReturn(true);
        when(otpService.initiateLoginOtp(testUser.getEmail())).thenReturn("challenge-123");

        LoginResponse result = authService.login(loginRequest, response);

        assertTrue(result.requiresOtp());
        assertEquals("challenge-123", result.challengeToken());
        assertEquals(testUser.getEmail(), result.email());
        assertNull(result.auth());
        verify(authCookieUtil, never()).setAuthCookies(any(), any(), any());
    }

    @Test
    void login_Failure_InvalidCredentials() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        assertThrows(BadCredentialsException.class, () -> authService.login(loginRequest, response));
    }

    @Test
    void login_Failure_InactiveUser() {
        testUser.setActive(false);
        Authentication authentication = mock(Authentication.class);
        AuthUser authUser = new AuthUser(testUser);
        when(authentication.getPrincipal()).thenReturn(authUser);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));

        assertThrows(InactiveAccountException.class, () -> authService.login(loginRequest, response));
    }

    @Test
    void login_Failure_UnverifiedUser() {
        testUser.setEmailVerified(false);
        Authentication authentication = mock(Authentication.class);
        AuthUser authUser = new AuthUser(testUser);
        when(authentication.getPrincipal()).thenReturn(authUser);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));

        assertThrows(InactiveAccountException.class, () -> authService.login(loginRequest, response));
    }

    @Test
    void register_Success() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        authService.register(registrationRequest);

        verify(userRepository).save(any(User.class));
        verify(otpService).sendRegistrationOtp(anyString());
    }

    @Test
    void register_Failure_PasswordsDoNotMatch() {
        RegistrationRequest invalidRequest = new RegistrationRequest("test@example.com", "Test User", "password", "wrongPassword");

        assertThrows(BadRequestException.class, () -> authService.register(invalidRequest));
    }

    @Test
    void register_Failure_EmailAlreadyExists() {
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> authService.register(registrationRequest));
    }

    @Test
    void verifyRegistrationOtp_Success() {
        when(otpService.verifyRegistrationOtp(anyString(), anyString())).thenReturn(true);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(frontDeskAccessService.getEffectivePermissions(testUser)).thenReturn(Set.of());

        AuthResponse result = authService.verifyRegistrationOtp(otpRequest, response, request);

        assertNotNull(result);
        assertTrue(testUser.isEmailVerified());
        assertTrue(testUser.isActive());
        verify(authCookieUtil).setAuthCookies(request, response, testUser);
    }

    @Test
    void verifyRegistrationOtp_Failure_InvalidOtp() {
        when(otpService.verifyRegistrationOtp(anyString(), anyString())).thenReturn(false);

        assertThrows(BadRequestException.class, () -> authService.verifyRegistrationOtp(otpRequest, response, request));
    }

    @Test
    void verifyRegistrationOtp_Failure_UserNotFound() {
        when(otpService.verifyRegistrationOtp(anyString(), anyString())).thenReturn(true);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authService.verifyRegistrationOtp(otpRequest, response, request));
    }

    @Test
    void verifyLoginOtp_Success() {
        when(otpService.getEmailFromLoginChallenge("challenge-123")).thenReturn(testUser.getEmail());
        when(otpService.verifyLoginOtp("challenge-123", "123456")).thenReturn(true);
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(frontDeskAccessService.getEffectivePermissions(testUser)).thenReturn(Set.of());

        AuthResponse result = authService.verifyLoginOtp(loginOtpVerificationRequest, response);

        assertNotNull(result);
        assertEquals(testUser.getEmail(), result.email());
        verify(authCookieUtil).setAuthCookies(null, response, testUser);
    }

    @Test
    void verifyLoginOtp_Failure_InvalidChallenge() {
        when(otpService.getEmailFromLoginChallenge("challenge-123")).thenReturn(null);

        assertThrows(BadRequestException.class, () -> authService.verifyLoginOtp(loginOtpVerificationRequest, response));
    }

    @Test
    void verifyLoginOtp_Failure_InvalidOtp() {
        when(otpService.getEmailFromLoginChallenge("challenge-123")).thenReturn(testUser.getEmail());
        when(otpService.verifyLoginOtp("challenge-123", "123456")).thenReturn(false);

        assertThrows(BadRequestException.class, () -> authService.verifyLoginOtp(loginOtpVerificationRequest, response));
    }

    @Test
    void resendRegistrationOtp_Success() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));

        authService.resendRegistrationOtp("test@example.com");

        verify(otpService).sendRegistrationOtp(testUser.getEmail());
    }

    @Test
    void resendRegistrationOtp_Failure_UserNotFound() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authService.resendRegistrationOtp("test@example.com"));
    }

    @Test
    void resendLoginOtp_Success() {
        authService.resendLoginOtp(loginOtpResendRequest);

        verify(otpService).resendLoginOtp("challenge-123");
    }

    @Test
    void loggedInUser_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(frontDeskAccessService.getEffectivePermissions(testUser)).thenReturn(Set.of());

        AuthResponse result = authService.loggedInUser();

        assertNotNull(result);
        assertEquals(testUser.getEmail(), result.email());
        assertTrue(result.permissions().isEmpty());
    }

    @Test
    void loggedInUser_FrontDeskIncludesEffectivePermissions() {
        testUser.setRoles(UserRole.FRONT_DESK);
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(frontDeskAccessService.getEffectivePermissions(testUser))
                .thenReturn(Set.of(FrontDeskPermission.WALK_IN_ORDER_CREATE, FrontDeskPermission.CUSTOMER_SEARCH));

        AuthResponse result = authService.loggedInUser();

        assertEquals(UserRole.FRONT_DESK, result.role());
        assertEquals(Set.of(FrontDeskPermission.WALK_IN_ORDER_CREATE, FrontDeskPermission.CUSTOMER_SEARCH), result.permissions());
    }

    @Test
    void logout_Success() {
        authService.logout(response);
        verify(authCookieUtil).removeAuthCookies(response);
    }

    @Test
    void forgotPassword_Success() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));

        authService.forgotPassword("test@example.com");

        verify(otpService).requestResetPasswordToken(testUser.getEmail(), testUser.getFullName());
    }

    @Test
    void forgotPassword_UserNotFound() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        authService.forgotPassword("test@example.com");

        verify(otpService, never()).requestResetPasswordToken(anyString(), anyString());
    }

    @Test
    void resetPassword_Success() {
        String token = "valid-token";
        ResetPasswordRequest resetRequest = new ResetPasswordRequest("newPassword", "newPassword");
        when(otpService.verifyResetPasswordToken(token)).thenReturn(true);
        when(otpService.getEmailFromResetPasswordToken(token)).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        authService.resetPassword(resetRequest, token);

        verify(userRepository).save(testUser);
        verify(otpService).invalidateResetPasswordToken(token);
    }

    @Test
    void resetPassword_Failure_InvalidToken() {
        when(otpService.verifyResetPasswordToken(anyString())).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> authService.resetPassword(new ResetPasswordRequest("p", "p"), "invalid"));
    }

    @Test
    void resetPassword_Failure_PasswordsDoNotMatch() {
        when(otpService.verifyResetPasswordToken(anyString())).thenReturn(true);
        ResetPasswordRequest resetRequest = new ResetPasswordRequest("pass1", "pass2");

        assertThrows(BadRequestException.class, () -> authService.resetPassword(resetRequest, "token"));
    }

    @Test
    void resetPassword_Failure_SameAsOldPassword() {
        String token = "valid-token";
        ResetPasswordRequest resetRequest = new ResetPasswordRequest("oldPassword", "oldPassword");
        when(otpService.verifyResetPasswordToken(token)).thenReturn(true);
        when(otpService.getEmailFromResetPasswordToken(token)).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThrows(BadRequestException.class, () -> authService.resetPassword(resetRequest, token));
    }

    @Test
    void refreshAccessToken_Success() {
        authService.refreshAccessToken("refreshToken", response);
        verify(authCookieUtil).refreshAccessToken("refreshToken", response);
    }
}
