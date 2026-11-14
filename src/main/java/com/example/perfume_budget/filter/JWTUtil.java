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

    public String generateAccessToken(JwtUserDetails user){
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        claims.put("userId", user.getId());
        claims.put("fullName", user.getFullName());
        putTenantClaim(claims);
        return generateToken(user.getEmail(), jwtAccessExpirationMs, claims);
    }

    public String generateAccessToken(String email, String role, Long userId, String fullName){
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("userId", userId);
        claims.put("fullName", fullName);
        putTenantClaim(claims);
        return generateToken(email, jwtAccessExpirationMs, claims);
    }


    public String generateRefreshToken(String email, String role, Long userId, String fullName){
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("fullName", fullName);
        putTenantClaim(claims);
        return generateToken(email, jwtRefreshExpirationMs, claims);
    }

    public String generateRefreshToken(JwtUserDetails user){
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("role", user.getRole());
        claims.put("fullName", user.getFullName());
        putTenantClaim(claims);
        return generateToken(user.getEmail(), jwtRefreshExpirationMs, claims);
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
            log.error("JWT token expired: {}", e.getMessage());
            throw new InvalidJWTTokenException("Expired JWT token");
        } catch (JwtException exception ) {
            log.error("Invalid JWT token: {}", exception.getMessage());
            throw new InvalidJWTTokenException("Invalid JWT token");
        } catch (Exception e){
            log.error("Error validating JWT token: {}", e.getMessage());
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
