package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.auth.request.LoginRequest;
import com.example.perfume_budget.dto.auth.request.LoginOtpResendRequest;
import com.example.perfume_budget.dto.auth.request.LoginOtpVerificationRequest;
import com.example.perfume_budget.dto.auth.request.OtpVerificationRequest;
import com.example.perfume_budget.dto.auth.request.RegistrationRequest;
import com.example.perfume_budget.dto.auth.request.ResetPasswordRequest;
import com.example.perfume_budget.dto.auth.response.AuthResponse;
import com.example.perfume_budget.dto.auth.response.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request, HttpServletResponse response);
    void register(RegistrationRequest request);

    AuthResponse verifyRegistrationOtp(OtpVerificationRequest otpRequest, HttpServletResponse response, HttpServletRequest request);
    AuthResponse verifyLoginOtp(LoginOtpVerificationRequest otpRequest, HttpServletResponse response);

    void resendRegistrationOtp(String email);
    void resendLoginOtp(LoginOtpResendRequest request);

    AuthResponse loggedInUser();
    void logout(HttpServletResponse response);

    void forgotPassword(String email);

    void resetPassword(ResetPasswordRequest resetPasswordRequest, String resetPasswordToken);

    void refreshAccessToken(String refreshToken, HttpServletResponse response);
}
