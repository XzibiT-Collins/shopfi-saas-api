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

    /** An untyped token valid for {@code lifetimeMs} from now (the claim set of a pre-SEC-03e token). */
    private String untyped(long lifetimeMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject("a@b.test").issuedAt(new Date(now)).expiration(new Date(now + lifetimeMs))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(KEY))).compact();
    }

    /** What an old refresh token looked like: the refresh lifetime (24 h here), above the 1 h access lifetime. */
    private String untyped() {
        return untyped(86_400_000L);
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

    /** FIX-B: during the grace an untyped token must also have lived longer than an access token, or it is just an old access token. */
    @Test
    void duringTheGraceAnUntypedTokenWithAnAccessLifetimeIsNotARefreshToken() {
        JWTUtil u = util("2099-01-01T00:00:00Z");
        assertThat(u.isRefreshToken(untyped(86_400_000L))).as("refresh lifetime: accepted").isTrue();
        assertThat(u.isRefreshToken(untyped(3_600_001L + 1_000L))).as("just above the access lifetime: accepted").isTrue();
        assertThat(u.isRefreshToken(untyped(3_600_000L))).as("exactly the access lifetime: refused").isFalse();
        assertThat(u.isRefreshToken(untyped(600_000L))).as("shorter than the access lifetime: refused").isFalse();
        assertThat(u.isRefreshToken(untyped(1_000L))).isFalse();
    }

    /** FIX-G: lowering the access TTL during the grace must not turn an old untyped 1 h ACCESS token into a refresh token. */
    @Test
    void theLegacyAccessTtlKeepsTheLifetimeRuleSoundWhenTheAccessTtlIsLoweredDuringTheGrace() {
        JWTUtil u = util("2099-01-01T00:00:00Z");
        ReflectionTestUtils.setField(u, "jwtAccessExpirationMs", 1_800_000L); // lowered from 1 h to 30 min
        String oldUntypedAccess = untyped(3_600_000L);                       // minted when the access TTL was 1 h
        assertThat(u.isRefreshToken(oldUntypedAccess)).as("without the setting the lowered TTL lets it through").isTrue();

        ReflectionTestUtils.setField(u, "untypedGraceLegacyAccessTtlSeconds", "3600");
        assertThat(u.isRefreshToken(oldUntypedAccess)).isFalse();
        assertThat(u.isRefreshToken(untyped(86_400_000L))).as("a real legacy refresh token is unaffected").isTrue();

        ReflectionTestUtils.setField(u, "untypedGraceLegacyAccessTtlSeconds", "garbage");
        assertThat(u.isRefreshToken(oldUntypedAccess)).as("malformed value = current TTL only").isTrue();
        ReflectionTestUtils.setField(u, "untypedGraceLegacyAccessTtlSeconds", "-5");
        assertThat(u.isRefreshToken(untyped(86_400_000L))).isTrue();
    }

    @Test
    void anUntypedTokenWithoutIssuedAtOrExpiryIsNotARefreshTokenEvenDuringTheGrace() {
        JWTUtil u = util("2099-01-01T00:00:00Z");
        String noIat = Jwts.builder().subject("a@b.test").expiration(new Date(System.currentTimeMillis() + 86_400_000L))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(KEY))).compact();
        String noExp = Jwts.builder().subject("a@b.test").issuedAt(new Date())
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(KEY))).compact();
        assertThat(u.isRefreshToken(noIat)).isFalse();
        assertThat(u.isRefreshToken(noExp)).isFalse();
    }

    @Test
    void theLifetimeRuleNeverAffectsTypedTokens() {
        JWTUtil u = util("");
        assertThat(u.isRefreshToken(u.generateRefreshToken("a@b.test", "ADMIN", 1L, "A B"))).isTrue();
        ReflectionTestUtils.setField(u, "jwtRefreshExpirationMs", 60_000L); // a refresh token shorter than the access one is still typed
        assertThat(u.isRefreshToken(u.generateRefreshToken("a@b.test", "ADMIN", 1L, "A B"))).isTrue();
    }
}
