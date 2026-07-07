package com.example.perfume_budget.security.oauth2;

import java.util.Map;

/**
 * Abstract base class for OAuth2 user information from different providers
 * Provides a unified interface to extract user data regardless of the provider
 */
public abstract class OAuth2UserInfo {
    protected Map<String, Object> attributes;

    public OAuth2UserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    /**
     * Get the unique identifier from the OAuth2 provider
     */
    public abstract String getId();

    /**
     * Get the user's full name from the OAuth2 provider
     */
    public abstract String getName();

    /**
     * Get the user's email from the OAuth2 provider
     */
    public abstract String getEmail();

    /**
     * Get the user's profile image URL from the OAuth2 provider
     */
    public abstract String getImageUrl();

    /**
     * Get all attributes returned by the OAuth2 provider
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
