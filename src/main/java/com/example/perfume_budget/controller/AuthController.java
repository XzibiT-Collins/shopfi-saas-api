package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.auth.request.*;
import com.example.perfume_budget.dto.auth.response.AuthResponse;
import com.example.perfume_budget.dto.auth.response.LoginResponse;
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
    public ResponseEntity<CustomApiResponse<?>> resendOtp(@RequestBody EmailRequest request){
        authService.resendRegistrationOtp(request.email());
        return ResponseEntity.ok(CustomApiResponse.success("OTP resent successfully"));
    }

    @PostMapping("/resend-login-otp")
    public ResponseEntity<CustomApiResponse<?>> resendLoginOtp(@Valid @RequestBody LoginOtpResendRequest request){
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

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response){
        authService.logout(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<CustomApiResponse<?>> refreshToken(@CookieValue(name="refreshToken") String refreshToken, HttpServletResponse response){
        authService.refreshAccessToken(refreshToken, response);
        return ResponseEntity.ok(CustomApiResponse.success("Token refreshed successfully"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<CustomApiResponse<Void>> forgotPassword(@Valid @RequestBody EmailRequest request){
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
