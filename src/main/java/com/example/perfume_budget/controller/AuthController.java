package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.auth.request.*;
import com.example.perfume_budget.dto.auth.response.AuthResponse;
import com.example.perfume_budget.dto.auth.response.LoginResponse;
import com.example.perfume_budget.security.AuthRateLimiter;
import com.example.perfume_budget.security.ClientIps;
import com.example.perfume_budget.service.interfaces.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final AuthRateLimiter authRateLimiter;
    private final com.example.perfume_budget.utils.AuthUserUtil authUserUtil;

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegistrationRequest request){
        authService.register(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<CustomApiResponse<AuthResponse>> verifyOtp(@Valid @RequestBody OtpVerificationRequest otpRequest, HttpServletResponse response, HttpServletRequest request){
        AuthResponse authResponse = authService.verifyRegistrationOtp(otpRequest, response, request);
        return ResponseEntity.ok(CustomApiResponse.success("OTP verified successfully", authResponse));
    }

    @PostMapping("/verify-login-otp")
    public ResponseEntity<CustomApiResponse<AuthResponse>> verifyLoginOtp(@Valid @RequestBody LoginOtpVerificationRequest otpRequest,
                                                                          HttpServletResponse response) {
        return ResponseEntity.ok(CustomApiResponse.success("Login OTP verified successfully",
                authService.verifyLoginOtp(otpRequest, response)));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<CustomApiResponse<?>> resendOtp(@Valid @RequestBody EmailRequest request, HttpServletRequest httpRequest){
        // Throttle on the input (per IP, per IP+tenant+email and a per-email ceiling) before any account lookup.
        authRateLimiter.checkResendOtp(ClientIps.rateLimitKey(httpRequest), request.email());
        authService.resendRegistrationOtp(request.email());
        return ResponseEntity.ok(CustomApiResponse.success("OTP resent successfully"));
    }

    @PostMapping("/resend-login-otp")
    public ResponseEntity<CustomApiResponse<?>> resendLoginOtp(@Valid @RequestBody LoginOtpResendRequest request,
                                                               HttpServletRequest httpRequest){
        authRateLimiter.checkResendLoginOtp(ClientIps.rateLimitKey(httpRequest));
        authService.resendLoginOtp(request);
        return ResponseEntity.ok(CustomApiResponse.success("Login OTP resent successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<CustomApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response){
        return ResponseEntity.ok().body(
                CustomApiResponse.success(
                        authService.login(request,response)
                )
        );
    }

//    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    @GetMapping("/me")
    public ResponseEntity<CustomApiResponse<AuthResponse>> getAuthenticatedUser(){
        AuthResponse authResponse = authService.loggedInUser();
        return ResponseEntity.ok(CustomApiResponse.success("Authenticated user fetched successfully", authResponse));
    }

    /** API-RD-08: the caller edits their own full name (any signed-in tenant role; the target is always the principal). */
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','FRONT_DESK')")
    @PatchMapping("/me")
    public ResponseEntity<CustomApiResponse<AuthResponse>> updateProfile(@Valid @RequestBody UpdateProfileRequest request){
        return ResponseEntity.ok(CustomApiResponse.success("Profile updated successfully", authService.updateProfile(request)));
    }

    /**
     * API-RD-08: change the caller's own password. Needs the current password, applies the signup/reset policy,
     * revokes every older token (any device) and re-issues this session's cookies.
     */
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','FRONT_DESK')")
    @PostMapping("/change-password")
    public ResponseEntity<CustomApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                                  HttpServletRequest httpRequest,
                                                                  HttpServletResponse response){
        com.example.perfume_budget.model.User current = authUserUtil.getCurrentUser();
        if (current == null) {
            throw new org.springframework.security.authentication.BadCredentialsException("Not authenticated");
        }
        authRateLimiter.checkChangePassword(ClientIps.rateLimitKey(httpRequest), current.getId());
        authService.changePassword(request, response);
        return ResponseEntity.ok(CustomApiResponse.success("Password changed successfully"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response){
        authService.logout(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<CustomApiResponse<?>> refreshToken(@CookieValue(name="refreshToken", required = false) String refreshToken, HttpServletResponse response){
        authService.refreshAccessToken(refreshToken, response);
        return ResponseEntity.ok(CustomApiResponse.success("Token refreshed successfully"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<CustomApiResponse<Void>> forgotPassword(@Valid @RequestBody EmailRequest request,
                                                                  HttpServletRequest httpRequest){
        authRateLimiter.checkForgotPassword(ClientIps.rateLimitKey(httpRequest), request.email());
        authService.forgotPassword(request.email());
        return ResponseEntity.ok(CustomApiResponse.success("Password reset email sent"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<CustomApiResponse<?>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            @NotBlank @RequestParam("resetPasswordToken") String resetPasswordToken
    ){
        authService.resetPassword(request, resetPasswordToken);
        return ResponseEntity.ok(CustomApiResponse.success("Password has been reset successfully"));
    }
}
