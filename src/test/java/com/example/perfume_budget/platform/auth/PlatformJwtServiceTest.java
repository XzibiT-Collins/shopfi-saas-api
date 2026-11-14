package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.filter.JWTUtil;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformJwtServiceTest {

    static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    final PlatformJwtService svc = new PlatformJwtService(SECRET, new PlatformAuthProperties());

    private String forge(javax.crypto.SecretKey key, String tenant, String role, String typ, long ttlMs) {
        return Jwts.builder().subject("root@x.test").claim("tenant", tenant).claim("role", role).claim("typ", typ)
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + ttlMs)).signWith(key).compact();
    }

    @Test
    void issuedTokens_carryPlatformClaimRoleAndType_andVerifyOnlyForTheirType() {
        String access = svc.issueAccess(7L, "root@x.test", "Root");
        String refresh = svc.issueRefresh(7L, "root@x.test", "Root");
        var c = svc.verify(access, PlatformJwtService.TYPE_ACCESS).orElseThrow();
        assertThat(c.get("tenant")).isEqualTo("__platform__");
        assertThat(c.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(c.getSubject()).isEqualTo("root@x.test");
        assertThat(svc.verify(refresh, PlatformJwtService.TYPE_REFRESH)).isPresent();
        assertThat(svc.verify(refresh, PlatformJwtService.TYPE_ACCESS)).isEmpty();
        assertThat(svc.verify(access, PlatformJwtService.TYPE_REFRESH)).isEmpty();
    }

    @Test
    void tenantTokenSignedWithTenantKey_neverVerifiesAsPlatform_evenWithForgedClaims() {
        JWTUtil tenantUtil = new JWTUtil();
        ReflectionTestUtils.setField(tenantUtil, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(tenantUtil, "jwtAccessExpirationMs", 60_000L);
        String tenantToken = tenantUtil.generateAccessToken("a@b.test", "ADMIN", 1L, "A");
        assertThat(svc.verify(tenantToken, PlatformJwtService.TYPE_ACCESS)).isEmpty();
        // same key as the tenant one, but with platform claims: still rejected (derived platform key differs)
        var tenantKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(SECRET));
        assertThat(svc.verify(forge(tenantKey, "__platform__", "SUPER_ADMIN", "access", 60_000), "access")).isEmpty();
    }

    @Test
    void platformKeyToken_withWrongClaims_isRejected() {
        var key = PlatformJwtService.deriveKey(SECRET);
        assertThat(svc.verify(forge(key, "__platform__", "SUPER_ADMIN", "access", 60_000), "access")).isPresent();
        assertThat(svc.verify(forge(key, "acme", "SUPER_ADMIN", "access", 60_000), "access")).as("tenant claim").isEmpty();
        assertThat(svc.verify(forge(key, null, "SUPER_ADMIN", "access", 60_000), "access")).as("no tenant").isEmpty();
        assertThat(svc.verify(forge(key, "__platform__", "ADMIN", "access", 60_000), "access")).as("role").isEmpty();
        assertThat(svc.verify(forge(key, "__platform__", "SUPER_ADMIN", null, 60_000), "access")).as("no typ").isEmpty();
        assertThat(svc.verify(forge(key, "__platform__", "SUPER_ADMIN", "access", -1_000), "access")).as("expired").isEmpty();
    }

    @Test
    void platformKeyToken_withoutExpOrIat_isRejected() {
        var key = PlatformJwtService.deriveKey(SECRET);
        String noExp = Jwts.builder().subject("root@x.test").claim("tenant", "__platform__").claim("role", "SUPER_ADMIN")
                .claim("typ", "access").issuedAt(new Date()).signWith(key).compact();
        String noIat = Jwts.builder().subject("root@x.test").claim("tenant", "__platform__").claim("role", "SUPER_ADMIN")
                .claim("typ", "access").expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();
        assertThat(svc.verify(noExp, "access")).as("no exp").isEmpty();
        assertThat(svc.verify(noIat, "access")).as("no iat").isEmpty();
    }

    @Test
    void garbageAndNull_areRejectedWithoutThrowing() {
        assertThat(svc.verify(null, "access")).isEmpty();
        assertThat(svc.verify("", "access")).isEmpty();
        assertThat(svc.verify("not.a.jwt", "access")).isEmpty();
    }
}
