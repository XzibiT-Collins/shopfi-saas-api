package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformAdminBootstrapTest {

    PlatformAdminRepository repo = mock(PlatformAdminRepository.class);
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private PlatformAdminBootstrap boot(String email, String pw) {
        return new PlatformAdminBootstrap(repo, encoder, email, pw, "");
    }

    @Test
    void unsetEnv_skipsWithoutTouchingTheDatabase() {
        assertThat(boot("", "").bootstrap()).isFalse();
        assertThat(boot(null, null).bootstrap()).isFalse();
        assertThat(boot("root@x.test", "").bootstrap()).isFalse();
        assertThat(boot("", "Str0ng-enough-pw").bootstrap()).isFalse();
        verifyNoInteractions(repo);
    }

    @Test
    void weakOrOversizedPassword_orBadEmail_isRefused() {
        assertThat(boot("root@x.test", "short").bootstrap()).isFalse();
        assertThat(boot("root@x.test", "x".repeat(73)).bootstrap()).isFalse();
        assertThat(boot("not-an-email", "Str0ng-enough-pw").bootstrap()).isFalse();
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void createsActiveAdmin_withBcryptHash_neverThePlaintext() {
        when(repo.existsByEmail("root@x.test")).thenReturn(false);
        assertThat(boot(" Root@X.test ", "Str0ng-enough-pw").bootstrap()).isTrue();
        var cap = org.mockito.ArgumentCaptor.forClass(PlatformAdmin.class);
        verify(repo).saveAndFlush(cap.capture());
        PlatformAdmin a = cap.getValue();
        assertThat(a.getEmail()).isEqualTo("root@x.test");
        assertThat(a.isActive()).isTrue();
        assertThat(a.getPassword()).startsWith("$2").isNotEqualTo("Str0ng-enough-pw");
        assertThat(encoder.matches("Str0ng-enough-pw", a.getPassword())).isTrue();
    }

    @Test
    void existingAdmin_isNeverModified() {
        when(repo.existsByEmail("root@x.test")).thenReturn(true);
        assertThat(boot("root@x.test", "An0ther-new-pass!").bootstrap()).isFalse();
        verify(repo, never()).saveAndFlush(any());
        verify(repo, never()).save(any());
    }
}
