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
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.DuplicateResourceException;
import com.example.perfume_budget.exception.InactiveAccountException;
import com.example.perfume_budget.mapper.UserMapper;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.security.AuthUser;
import com.example.perfume_budget.service.interfaces.AuthService;
import com.example.perfume_budget.service.interfaces.FrontDeskAccessService;
import com.example.perfume_budget.service.interfaces.FeatureFlagService;
import com.example.perfume_budget.service.interfaces.OtpService;
import com.example.perfume_budget.utils.AuthCookieUtil;
import com.example.perfume_budget.utils.AuthUserUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final AuthCookieUtil authCookieUtil;
    private final PasswordEncoder passwordEncoder;
    private final AuthUserUtil authUserUtil;
    private final OtpService otpService;
    private final FeatureFlagService featureFlagService;
    private final FrontDeskAccessService frontDeskAccessService;

    @Override
    public LoginResponse login(LoginRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.email().strip().toLowerCase(),request.password().strip())
        );

        AuthUser authUser = (AuthUser) authentication.getPrincipal();
        User user = getActiveUserByEmail(authUser.getUsername());

        if(!user.isEmailVerified()){
            throw new InactiveAccountException("Email address has not been verified.");
        }

        if(featureFlagService.isEnabled(FeatureFlagKey.LOGIN_OTP, user.getRoles())){
            String challengeToken = otpService.initiateLoginOtp(user.getEmail());
            return LoginResponse.builder()
                    .requiresOtp(true)
                    .challengeToken(challengeToken)
                    .email(user.getEmail())
                    .auth(null)
                    .build();
        }

        // Generate JWT tokens and set cookies
        authCookieUtil.setAuthCookies(null, response, user);
        return LoginResponse.builder()
                .requiresOtp(false)
                .challengeToken(null)
                .email(user.getEmail())
                .auth(buildAuthResponse(user))
                .build();
    }

    @Override
    public void register(RegistrationRequest request) {
        passwordsMatch(request.password(), request.confirmPassword());
        checkDuplicateRegistration(request.email().strip().toLowerCase());

        User newUser = UserMapper.toUser(request);
        newUser.setPassword(passwordEncoder.encode(request.password().strip()));

        User user = userRepository.save(newUser);
        otpService.sendRegistrationOtp(user.getEmail());
    }

    @Override
    public AuthResponse verifyRegistrationOtp(OtpVerificationRequest otpRequest, HttpServletResponse response, HttpServletRequest request){
        String normalizedEmail = otpRequest.email().strip().toLowerCase();
        boolean isValid = otpService.verifyRegistrationOtp(normalizedEmail, otpRequest.otp());

        if(!isValid){
            throw new BadRequestException("Invalid or expired OTP");
        }
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        user.setEmailVerified(true);
        user.setActive(true);

        User savedUser = userRepository.save(user);
        log.info("User {} verified successfully", savedUser.getEmail());

        authCookieUtil.setAuthCookies(request,response, savedUser);
        return buildAuthResponse(savedUser);
    }

    @Override
    public AuthResponse verifyLoginOtp(LoginOtpVerificationRequest otpRequest, HttpServletResponse response) {
        String email = otpService.getEmailFromLoginChallenge(otpRequest.challengeToken());
        if(email == null){
            throw new BadRequestException("Invalid or expired login challenge");
        }

        boolean isValid = otpService.verifyLoginOtp(otpRequest.challengeToken(), otpRequest.otp());
        if(!isValid){
            throw new BadRequestException("Invalid or expired OTP");
        }

        User user = getActiveUserByEmail(email.strip().toLowerCase());
        authCookieUtil.setAuthCookies(null, response, user);
        return buildAuthResponse(user);
    }

    @Override
    public void resendRegistrationOtp(String email) {
        User user = userRepository.findByEmail(email.strip().toLowerCase())
                .orElseThrow(() -> new BadCredentialsException("User not found"));
        otpService.sendRegistrationOtp(user.getEmail());
    }

    @Override
    public void resendLoginOtp(LoginOtpResendRequest request) {
        otpService.resendLoginOtp(request.challengeToken());
    }

    @Override
    public AuthResponse loggedInUser() {
        User currentUser = authUserUtil.getCurrentUser();
        return buildAuthResponse(currentUser);
    }

    @Override
    public void logout(HttpServletResponse response) {
        authCookieUtil.removeAuthCookies(response);
    }

    @Override
    public void forgotPassword(String email) {
        String normalizedEmail = email.strip().toLowerCase();
        userRepository.findByEmail(normalizedEmail)
                .ifPresent(user -> otpService.requestResetPasswordToken(normalizedEmail, user.getFullName()));
    }


    @Override
    public void resetPassword(ResetPasswordRequest resetPasswordRequest, String resetPasswordToken) {
        boolean isValid = otpService.verifyResetPasswordToken(resetPasswordToken);
        if(!isValid){
            throw new IllegalArgumentException("Invalid or expired token");
        }

        if(!resetPasswordRequest.password().equals(resetPasswordRequest.confirmPassword())){
            throw new BadRequestException("Passwords do not match");
        }

        String email = otpService.getEmailFromResetPasswordToken(resetPasswordToken);
        User user = getActiveUserByEmail(email.strip().toLowerCase());
        checkDuplicatePassword(user.getPassword(),resetPasswordRequest.password());
        user.setPassword(passwordEncoder.encode(resetPasswordRequest.password()));
        userRepository.save(user);

        log.info("Password reset successful for user {}", email);
        otpService.invalidateResetPasswordToken(resetPasswordToken);
    }

    @Override
    public void refreshAccessToken(String refreshToken, HttpServletResponse response){
        authCookieUtil.refreshAccessToken(refreshToken, response);
    }

    private void checkDuplicatePassword(String encodedPassword, String password) {
        if(passwordEncoder.matches(password,encodedPassword)){
            throw new BadRequestException("New password cannot be same as current password");
        }
    }

    private void checkDuplicateRegistration(String email) {
        if(userRepository.existsByEmail(email)){
            throw new DuplicateResourceException("An account with this email already exists.");
        }
    }

    private void passwordsMatch(String password, String confirmPassword) {
        if(!password.equals(confirmPassword)){
            throw new BadRequestException("Passwords do not match.");
        }
    }

    private User getActiveUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("User not found"));
        if(!user.isActive()){
            throw new InactiveAccountException("User account is inactive");
        }
        return user;
    }

    private AuthResponse buildAuthResponse(User user){
        Set<com.example.perfume_budget.enums.FrontDeskPermission> permissions =
                frontDeskAccessService.getEffectivePermissions(user);
        return AuthResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .profilePicture(user.getImageUrl() != null ? user.getImageUrl() : "")
                .role(user.getRoles())
                .permissions(permissions)
                .build();
    }
}
