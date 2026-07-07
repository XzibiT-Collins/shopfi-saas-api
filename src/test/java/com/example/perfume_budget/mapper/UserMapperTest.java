package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.auth.request.RegistrationRequest;
import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserMapperTest {

    @Test
    void toUser_Success() {
        RegistrationRequest request = new RegistrationRequest("test@test.com", "John Doe", "password", "password");

        User user = UserMapper.toUser(request);

        assertNotNull(user);
        assertEquals("test@test.com", user.getEmail());
        assertEquals(UserRole.CUSTOMER, user.getRoles());
        assertEquals(AuthProvider.LOCAL, user.getAuthProvider());
        assertFalse(user.isActive());
    }

    @Test
    void toUser_NullInput_ThrowsException() {
        assertThrows(NullPointerException.class, () -> UserMapper.toUser(null));
    }
}
