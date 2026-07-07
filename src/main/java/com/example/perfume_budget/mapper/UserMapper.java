package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.auth.request.RegistrationRequest;
import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.Profile;
import com.example.perfume_budget.model.User;

public class UserMapper {
    private UserMapper(){
        throw new IllegalStateException("Utility Class");
    }

    public static User toUser(RegistrationRequest request){
        Profile newProfile = Profile.builder().build();
        return User.builder()
                .email(request.email().strip().toLowerCase())
                .fullName(request.fullName())
                .roles(UserRole.CUSTOMER)
                .authProvider(AuthProvider.LOCAL)
                .isActive(false)
                .emailVerified(false)
                .profile(newProfile)
                .build();
    }
}
