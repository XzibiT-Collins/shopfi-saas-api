package com.example.perfume_budget.config.ws;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.io.Serial;
import java.util.Collection;
import java.util.Objects;

/**
 * The STOMP session principal (SEC-03c). Its {@link #getName()} is tenant-qualified ({@code <slug>:<email>}) because
 * Spring routes {@code convertAndSendToUser} by principal name across ALL sessions: with the bare e-mail, an admin
 * with the same e-mail in another tenant would receive the push.
 */
public class StompTenantAuthentication extends UsernamePasswordAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String tenantSlug;
    private final String email;

    public StompTenantAuthentication(String tenantSlug, String email, Object principal,
                                     Collection<? extends GrantedAuthority> authorities) {
        super(principal, null, authorities);
        this.tenantSlug = Objects.requireNonNull(tenantSlug, "tenantSlug");
        this.email = Objects.requireNonNull(email, "email");
    }

    /** The user-destination name of {@code email} in tenant {@code tenantSlug}; slugs never contain ':'. */
    public static String userName(String tenantSlug, String email) {
        return tenantSlug + ":" + email;
    }

    public String getTenantSlug() {
        return tenantSlug;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String getName() {
        return userName(tenantSlug, email);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StompTenantAuthentication other && super.equals(o) && tenantSlug.equals(other.tenantSlug);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + tenantSlug.hashCode();
    }
}
