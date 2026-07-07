package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.exception.UnauthorizedException;

import java.util.Map;

/**
 * Factory class for creating OAuth2UserInfo instances based on the provider
 * Supports multiple OAuth2 providers through a unified interface
 */
public class OAuth2UserInfoFactory {

    /**
     * Creates an appropriate OAuth2UserInfo instance based on the registration ID
     *
     * @param registrationId The OAuth2 provider registration ID (e.g., "google")
     * @param attributes The attributes returned by the OAuth2 provider
     * @return OAuth2UserInfo instance for the specific provider
     * @throws UnauthorizedException if the provider is not supported
     */
    public static OAuth2UserInfo getOAuth2UserInfo(String registrationId,
                                                    Map<String, Object> attributes) {
        if (registrationId.equalsIgnoreCase(AuthProvider.GOOGLE.toString())) {
            return new GoogleOAuth2UserInfo(attributes);
        } else {
            throw new UnauthorizedException("Sorry! Login with " + registrationId + " is not supported yet.");
        }
    }
}
