package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.platform.enums.PlatformRole;
import com.example.perfume_budget.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies platform super-admin JWTs.
 *
 * <p>Separation from tenant tokens is enforced twice: (1) the signing key is DERIVED from the tenant key with
 * HMAC-SHA256 over a fixed label, so a token minted for a tenant never verifies here and a platform token never
 * verifies in the tenant {@code JWTUtil}; (2) every token must carry {@code tenant="__platform__"},
 * {@code role=SUPER_ADMIN} and a {@code typ} of {@code access} or {@code refresh}, checked on every parse.
 * Tokens are never logged.
 */
@Component
public class PlatformJwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    static final String KEY_LABEL = "shopfi-platform-jwt-v1";

    private final SecretKey key;
    private final PlatformAuthProperties properties;

    public PlatformJwtService(@Value("${application.security.jwt.secret-key}") String jwtSecret,
                              PlatformAuthProperties properties) {
        this.key = deriveKey(jwtSecret);
        this.properties = properties;
    }

    static SecretKey deriveKey(String jwtSecret) {
        try {
            byte[] base = Decoders.BASE64.decode(jwtSecret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(base, "HmacSHA256"));
            return Keys.hmacShaKeyFor(mac.doFinal(KEY_LABEL.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive platform JWT key", e);
        }
    }

    public String issueAccess(Long adminId, String email, String fullName) {
        return issue(adminId, email, fullName, TYPE_ACCESS, properties.getAccessTtl());
    }

    public String issueRefresh(Long adminId, String email, String fullName) {
        return issue(adminId, email, fullName, TYPE_REFRESH, properties.getRefreshTtl());
    }

    private String issue(Long adminId, String email, String fullName, String type, Duration ttl) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim("tenant", TenantContext.PLATFORM_SLUG)
                .claim("role", PlatformRole.SUPER_ADMIN.name())
                .claim("typ", type)
                .claim("userId", adminId)
                .claim("fullName", fullName)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** The verified claims when the token is a well-formed, unexpired platform SUPER_ADMIN token of {@code type}. */
    public Optional<Claims> verify(String token, String type) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!TenantContext.PLATFORM_SLUG.equals(claims.get("tenant", String.class))
                    || !PlatformRole.SUPER_ADMIN.name().equals(claims.get("role", String.class))
                    || !type.equals(claims.get("typ", String.class))
                    || claims.getSubject() == null
                    || claims.getExpiration() == null
                    || claims.getIssuedAt() == null) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException | ClassCastException e) {
            return Optional.empty();
        }
    }
}
