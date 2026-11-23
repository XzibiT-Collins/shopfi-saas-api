package com.example.perfume_budget.filter;


import com.example.perfume_budget.exception.InvalidJWTTokenException;
import com.example.perfume_budget.interfaces.JwtUserDetails;
import com.example.perfume_budget.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component
public class JWTUtil {
    @Value("${application.security.jwt.secret-key}")
    private String jwtSecret;

    @Value("${application.security.jwt.expiration}")
    private long jwtAccessExpirationMs;

    @Value("${application.security.jwt.refresh-token.expiration}")
    private long jwtRefreshExpirationMs;

    /**
     * FIX-A2 (1): refresh tokens minted before the {@code typ} claim existed carry no type. They are accepted by
     * {@link #isRefreshToken} only while {@code now} is before this ISO-8601 instant (set it to deploy time plus the
     * refresh lifetime, after which every untyped token has expired anyway). Blank (default) = no grace: an untyped token
     * is refused. A malformed value also means no grace (fail closed).
     */
    @Value("${application.security.jwt.refresh-token.untyped-grace-until:}")
    private String untypedRefreshGraceUntil;

    /**
     * FIX-G (FIX-B low): the access-token lifetime in force when the legacy untyped tokens were minted, in seconds.
     * The untyped-grace lifetime check compares against {@code max(this, current access TTL)}, so lowering
     * {@code application.security.jwt.expiration} during the grace window cannot turn an old untyped ACCESS token
     * into an accepted refresh token. Blank/invalid/non-positive (default) = the current access TTL only.
     */
    @Value("${application.security.jwt.refresh-token.untyped-grace-legacy-access-ttl-seconds:}")
    private String untypedGraceLegacyAccessTtlSeconds;

    private volatile Clock clock = Clock.systemUTC();

    /** Test support. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Access-token lifetime in whole seconds (rounded up), the single source for the cookie Max-Age. */
    public int accessTokenTtlSeconds(){
        return toSeconds(jwtAccessExpirationMs);
    }

    /** Refresh-token lifetime in whole seconds (rounded up), the single source for the cookie Max-Age. */
    public int refreshTokenTtlSeconds(){
        return toSeconds(jwtRefreshExpirationMs);
    }

    private static int toSeconds(long millis){
        long seconds = (millis + 999) / 1000;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }

    private SecretKey getKey(){
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private String generateToken(String email, long expiration, Map<String, Object> claims){
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .claims(claims)
                .subject(email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getKey())
                .compact();
    }

    private String generateToken(String email, long expiration){
        return generateToken(email, expiration, new HashMap<>());
    }

    /** Binds the token to the tenant resolved for the current request (cross-tenant defense). */
    private void putTenantClaim(Map<String, Object> claims){
        String tenant = TenantContext.getSlug();
        if (tenant != null) {
            claims.put("tenant", tenant);
        }
    }

    /** SEC-03e F4: token type claim, so a refresh token can never be presented where an access token is required. */
    public static final String TYPE_CLAIM = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    /**
     * API-RD-08: session-revocation claim. A user's {@code token_version} is minted into every token; a token whose
     * claim differs from the user's current version is refused everywhere (REST, refresh, STOMP CONNECT). A token
     * without the claim is version 0, so tokens minted before the claim existed stay valid until the first bump.
     */
    public static final String TOKEN_VERSION_CLAIM = "tv";

    /** The token's version claim, 0 when absent or not a whole number (fail towards the lowest version). */
    public int extractTokenVersion(String token) {
        Object v = parseToken(token).get(TOKEN_VERSION_CLAIM);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** True when the token was minted at the user's current version (the revocation check). */
    public boolean tokenVersionMatches(String token, int currentVersion) {
        return extractTokenVersion(token) == currentVersion;
    }

    public String generateAccessToken(String email, String role, Long userId, String fullName, int tokenVersion){
        Map<String, Object> claims = new HashMap<>();
        claims.put(TYPE_CLAIM, TYPE_ACCESS);
        claims.put("role", role);
        claims.put("userId", userId);
        claims.put("fullName", fullName);
        claims.put(TOKEN_VERSION_CLAIM, tokenVersion);
        putTenantClaim(claims);
        return generateToken(email, jwtAccessExpirationMs, claims);
    }

    public String generateRefreshToken(String email, String role, Long userId, String fullName, int tokenVersion){
        Map<String, Object> claims = new HashMap<>();
        claims.put(TYPE_CLAIM, TYPE_REFRESH);
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("fullName", fullName);
        claims.put(TOKEN_VERSION_CLAIM, tokenVersion);
        putTenantClaim(claims);
        return generateToken(email, jwtRefreshExpirationMs, claims);
    }

    public String generateAccessToken(JwtUserDetails user){
        Map<String, Object> claims = new HashMap<>();
        claims.put(TYPE_CLAIM, TYPE_ACCESS);
        claims.put("role", user.getRole());
        claims.put("userId", user.getId());
        claims.put("fullName", user.getFullName());
        putTenantClaim(claims);
        return generateToken(user.getEmail(), jwtAccessExpirationMs, claims);
    }

    public String generateAccessToken(String email, String role, Long userId, String fullName){
        Map<String, Object> claims = new HashMap<>();
        claims.put(TYPE_CLAIM, TYPE_ACCESS);
        claims.put("role", role);
        claims.put("userId", userId);
        claims.put("fullName", fullName);
        putTenantClaim(claims);
        return generateToken(email, jwtAccessExpirationMs, claims);
    }


    public String generateRefreshToken(String email, String role, Long userId, String fullName){
        Map<String, Object> claims = new HashMap<>();
        claims.put(TYPE_CLAIM, TYPE_REFRESH);
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("fullName", fullName);
        putTenantClaim(claims);
        return generateToken(email, jwtRefreshExpirationMs, claims);
    }

    public String generateRefreshToken(JwtUserDetails user){
        Map<String, Object> claims = new HashMap<>();
        claims.put(TYPE_CLAIM, TYPE_REFRESH);
        claims.put("userId", user.getId());
        claims.put("role", user.getRole());
        claims.put("fullName", user.getFullName());
        putTenantClaim(claims);
        return generateToken(user.getEmail(), jwtRefreshExpirationMs, claims);
    }

    /** The {@code typ} claim ({@code access} / {@code refresh}), null for a token minted before it existed. */
    public String extractType(String token) {
        Object type = parseToken(token).get(TYPE_CLAIM);
        return type == null ? null : type.toString();
    }

    /** FIX-A2 (2): true only for a token explicitly typed {@code access}; a refresh or untyped token never authenticates REST. */
    public boolean isAccessToken(String token) {
        return TYPE_ACCESS.equals(extractType(token));
    }

    /**
     * FIX-A2 (1): true for a token typed {@code refresh}, or untyped while the legacy grace window
     * ({@code application.security.jwt.refresh-token.untyped-grace-until}) is open. An {@code access} token (or any other
     * type) is never a refresh token, so a stolen short-lived access token cannot be extended.
     */
    public boolean isRefreshToken(String token) {
        String type = extractType(token);
        if (TYPE_REFRESH.equals(type)) {
            return true;
        }
        return type == null && untypedGraceOpen() && livedLongerThanAnAccessToken(token);
    }

    /**
     * FIX-B (FIX-A2 optional hardening): during the untyped grace an untyped token must also have been MINTED with a
     * lifetime ({@code exp - iat}) above the access-token lifetime, which is what a refresh token always had. An old
     * untyped ACCESS token (lifetime = the access TTL, e.g. 15 min) therefore cannot be traded for a new access token
     * while the grace is open. A token without {@code iat} / {@code exp} is refused (fail closed). Legitimate untyped
     * refresh tokens are unaffected as long as the refresh lifetime is longer than the access lifetime (a deployment
     * where JWT_REFRESH_EXPIRATION is not above the access expiration has no meaningful refresh token anyway).
     */
    private boolean livedLongerThanAnAccessToken(String token) {
        Claims claims = parseToken(token);
        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        if (issuedAt == null || expiration == null) {
            return false;
        }
        long lifetimeSeconds = (expiration.getTime() - issuedAt.getTime()) / 1000;
        return lifetimeSeconds > Math.max(accessTokenTtlSeconds(), legacyAccessTtlSeconds());
    }

    private long legacyAccessTtlSeconds() {
        if (untypedGraceLegacyAccessTtlSeconds == null || untypedGraceLegacyAccessTtlSeconds.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(untypedGraceLegacyAccessTtlSeconds.strip()));
        } catch (NumberFormatException e) {
            log.warn("Ignoring a malformed untyped-grace-legacy-access-ttl-seconds value");
            return 0;
        }
    }

    private boolean untypedGraceOpen() {
        if (untypedRefreshGraceUntil == null || untypedRefreshGraceUntil.isBlank()) {
            return false;
        }
        try {
            return clock.instant().isBefore(Instant.parse(untypedRefreshGraceUntil.strip()));
        } catch (DateTimeParseException e) {
            log.warn("Ignoring a malformed untyped-grace-until value; untyped refresh tokens are refused");
            return false;
        }
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token){
        return extractAllClaims(token).getSubject();
    }

    public boolean validateToken(String token, UserDetails userDetails){
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !extractAllClaims(token).getExpiration().before(new Date() ) && userDetails.isEnabled();
    }

    public void validateToken(String token){
        try {
            log.debug("Validating JWT token");
            var parser = Jwts.parser().verifyWith(getKey()).build();
            parser.parseSignedClaims(token);
            log.info("Valid token");
        } catch (ExpiredJwtException e) {
            log.info("JWT expired");
            throw new InvalidJWTTokenException("Expired JWT token");
        } catch (JwtException exception ) {
            log.error("Invalid JWT: {}", exception.getClass().getName()); // the message can quote header / claims
            throw new InvalidJWTTokenException("Invalid JWT token");
        } catch (Exception e){
            log.error("Error validating JWT: {}", e.getClass().getName());
            throw new InvalidJWTTokenException("Error validating JWT token");
        }
    }

    public Claims parseToken(String token) {
        var jws = Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token);
        return jws.getPayload();
    }

    public Long extractUserId(String token){
        return extractClaim(token, claims -> claims.get("userId", Long.class));
    }

    public String extractTenant(String token) {
        return extractClaim(token, claims -> {
            Object tenant = claims.get("tenant");
            return tenant != null ? tenant.toString() : null;
        });
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> {
            Object roleObj = claims.get("role");
            if (roleObj instanceof ArrayList<?> roles) {
                return roles.isEmpty() ? null : roles.getFirst().toString();
            }
            return roleObj != null ? roleObj.toString() : null;
        });
    }


    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = parseToken(token);
        return claimsResolver.apply(claims);
    }
}
