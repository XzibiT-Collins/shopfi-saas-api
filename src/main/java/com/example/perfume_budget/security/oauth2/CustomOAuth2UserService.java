package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.exception.UnauthorizedException;
import com.example.perfume_budget.model.Profile;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Custom OAuth2 User Service that handles user authentication via OAuth2
 * Extends DefaultOAuth2UserService to add custom user processing logic
 * Handles both new user registration and existing user updates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        try {
            return processOAuth2User(userRequest, oAuth2User);
        } catch (Exception ex) {
            log.error("Error processing OAuth2 user", ex);
            throw new OAuth2AuthenticationException(ex.getMessage());
        }
    }

    /**
     * Process the OAuth2 user - either register a new user or update existing user
     */
    private OAuth2User processOAuth2User(OAuth2UserRequest userRequest, OAuth2User oAuth2User) {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo oAuth2UserInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(
                registrationId,
                oAuth2User.getAttributes()
        );

        if (!StringUtils.hasText(oAuth2UserInfo.getEmail())) {
            throw new UnauthorizedException("Email not found from OAuth2 provider");
        }

        String normalizedEmail = oAuth2UserInfo.getEmail().strip().toLowerCase();
        Optional<User> userOptional = userRepository.findByEmail(normalizedEmail);
        User user;

        if (userOptional.isPresent()) {
            user = userOptional.get();

            // If user exists but signed up with different provider
            if (user.getAuthProvider() != null &&
                !user.getAuthProvider().equals(AuthProvider.valueOf(registrationId.toUpperCase()))) {
                throw new UnauthorizedException(
                        "Looks like you're signed up with " + user.getAuthProvider() +
                                " account. Please use your " + user.getAuthProvider() + " account to login."
                );
            }

            user = updateExistingUser(user, oAuth2UserInfo);
        } else {
            user = registerNewUser(userRequest, oAuth2UserInfo);
        }

        return new CustomOAuth2User(user, oAuth2User.getAttributes());
    }

    /**
     * Register a new user from OAuth2 authentication
     */
    private User registerNewUser(OAuth2UserRequest userRequest, OAuth2UserInfo oAuth2UserInfo) {
        User user = new User();
        user.setFullName(oAuth2UserInfo.getName());
        user.setEmail(oAuth2UserInfo.getEmail().strip().toLowerCase());
        user.setAuthProvider(AuthProvider.valueOf(
                userRequest.getClientRegistration().getRegistrationId().toUpperCase()
        ));
        user.setProviderId(oAuth2UserInfo.getId());
        user.setEmailVerified(true);
        user.setImageUrl(oAuth2UserInfo.getImageUrl());
        user.setRoles(UserRole.CUSTOMER);
        user.setActive(true);
        user.setPassword(""); // OAuth users don't have passwords

        // Create default profile
        Profile profile = new Profile();
        user.setProfile(profile);

        log.info("Registering new OAuth2 user: {}", user.getEmail());
        return userRepository.save(user);
    }

    /**
     * Update existing user with latest information from OAuth2 provider
     */
    private User updateExistingUser(User existingUser, OAuth2UserInfo oAuth2UserInfo) {
        existingUser.setFullName(oAuth2UserInfo.getName());
        existingUser.setImageUrl(oAuth2UserInfo.getImageUrl());
        existingUser.setEmailVerified(true);

        log.info("Updating existing OAuth2 user: {}", existingUser.getEmail());
        return userRepository.save(existingUser);
    }
}
