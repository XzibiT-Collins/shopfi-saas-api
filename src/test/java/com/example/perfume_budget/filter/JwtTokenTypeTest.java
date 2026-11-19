package com.example.perfume_budget.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** FIX-A2 (1)+(2): the token type decides what a JWT may be used for; untyped refresh tokens only during the grace window. */
class JwtTokenTypeTest {

    static final String KEY = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private JWTUtil util(String grace) {
        JWTUtil u = new JWTUtil();
        ReflectionTestUtils.setField(u, "jwtSecret", KEY);
        ReflectionTestUtils.setField(u, "jwtAccessExpirationMs", 3_600_000L);
        ReflectionTestUtils.setField(u, "jwtRefreshExpirationMs", 86_400_000L);
        ReflectionTestUtils.setField(u, "untypedRefreshGraceUntil", grace);
        u.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return u;
    }

    private String untyped() {
        return Jwts.builder().subject("a@b.test").issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(KEY))).compact();
    }

    @Test
    void accessTokenIsAccessAndNeverRefresh() {
        JWTUtil u = util("");
        String t = u.generateAccessToken("a@b.test", "ADMIN", 1L, "A B");
        assertThat(u.isAccessToken(t)).isTrue();
        assertThat(u.isRefreshToken(t)).isFalse();
    }

    @Test
    void refreshTokenIsRefreshAndNeverAccess() {
        JWTUtil u = util("");
        String t = u.generateRefreshToken("a@b.test", "ADMIN", 1L, "A B");
        assertThat(u.isRefreshToken(t)).isTrue();
        assertThat(u.isAccessToken(t)).isFalse();
    }

    @Test
    void anUntypedTokenIsNeverAnAccessTokenAndIsARefreshTokenOnlyDuringTheGrace() {
        String t = untyped();
        assertThat(util("").isAccessToken(t)).isFalse();
        assertThat(util(null).isRefreshToken(t)).isFalse();
        assertThat(util("").isRefreshToken(t)).isFalse();
        assertThat(util("   ").isRefreshToken(t)).isFalse();
        assertThat(util("2026-11-19T12:00:01Z").isRefreshToken(t)).isTrue();
        assertThat(util("2026-11-19T12:00:00Z").isRefreshToken(t)).as("grace ends exactly at the instant").isFalse();
        assertThat(util("2026-11-19T11:59:59Z").isRefreshToken(t)).isFalse();
        assertThat(util("not-a-date").isRefreshToken(t)).as("malformed grace fails closed").isFalse();
    }

    @Test
    void theGraceNeverMakesATypedAccessTokenARefreshToken() {
        JWTUtil u = util("2099-01-01T00:00:00Z");
        assertThat(u.isRefreshToken(u.generateAccessToken("a@b.test", "ADMIN", 1L, "A B"))).isFalse();
    }

    @Test
    void anUnknownTypeIsNeither() {
        String t = Jwts.builder().subject("a@b.test").claim("typ", "id").issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000)).signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(KEY))).compact();
        JWTUtil u = util("2099-01-01T00:00:00Z");
        assertThat(u.isAccessToken(t)).isFalse();
        assertThat(u.isRefreshToken(t)).isFalse();
    }
}
