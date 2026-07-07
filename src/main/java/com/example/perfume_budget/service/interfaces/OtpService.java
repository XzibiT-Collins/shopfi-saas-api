package com.example.perfume_budget.service.interfaces;

public interface OtpService {
    boolean verifyRegistrationOtp(String email, String otp);
    String initiateLoginOtp(String email);
    boolean verifyLoginOtp(String challengeToken, String otp);
    void resendLoginOtp(String challengeToken);
    String getEmailFromLoginChallenge(String challengeToken);
    boolean verifyResetPasswordToken(String passwordResetToken);
    String getEmailFromResetPasswordToken(String passwordResetToken);
    void sendRegistrationOtp(String email);
    void requestResetPasswordToken(String email, String fullName);
    void invalidateResetPasswordToken(String passwordResetToken);
}
