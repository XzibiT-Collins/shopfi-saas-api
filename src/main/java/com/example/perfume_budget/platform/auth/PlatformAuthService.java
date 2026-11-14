package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.platform.enums.PlatformRole;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Platform super-admin login / refresh / logout. Failure modes are deliberately indistinguishable. */
@Service
public class PlatformAuthService {

    static final String GENERIC_FAILURE = "Invalid email or password";
    static final String SESSION_FAILURE = "Invalid or expired session";

    private final PlatformAdminRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final PlatformJwtService jwt;
    private final PlatformAuthCookies cookies;
    private final PlatformLoginRateLimiter limiter;
    /** Hash of a random value, same encoder/cost as real hashes: compared when no usable admin exists. */
    private final String dummyHash;

    public PlatformAuthService(PlatformAdminRepository admins, PasswordEncoder passwordEncoder, PlatformJwtService jwt,
                               PlatformAuthCookies cookies, PlatformLoginRateLimiter limiter) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.cookies = cookies;
        this.limiter = limiter;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public PlatformAuthResponse login(PlatformLoginRequest request, String ipKey, HttpServletResponse response) {
        // Defense in depth behind bean validation: never feed BCrypt more than 72 bytes (silent truncation).
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PlatformLoginRequest.MAX_PASSWORD_BYTES) {
            throw new com.example.perfume_budget.exception.BadRequestException("Invalid request");
        }
        limiter.checkLoginIp(ipKey);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        limiter.checkLoginEmail(email);

        Optional<PlatformAdmin> found = admins.findByEmailIgnoreCase(email);
        // Always exactly one BCrypt compare: against the real hash when the admin exists (even if inactive, so an
        // inactive account costs the same and never answers differently), otherwise against the dummy hash.
        String hash = found.map(PlatformAdmin::getPassword).orElse(dummyHash);
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !passwordOk || !found.get().isActive()) {
            throw new BadCredentialsException(GENERIC_FAILURE);
        }
        PlatformAdmin admin = found.get();
        issueSession(admin, response, true);
        return toResponse(admin);
    }

    public PlatformAuthResponse refresh(HttpServletRequest request, String ipKey, HttpServletResponse response) {
        limiter.checkRefreshIp(ipKey);
        String token = PlatformAuthCookies.read(request, PlatformAuthCookies.REFRESH_COOKIE);
        Optional<PlatformAdmin> admin = jwt.verify(token, PlatformJwtService.TYPE_REFRESH)
                .map(Claims::getSubject)
                .flatMap(admins::findByEmailIgnoreCase)
                .filter(PlatformAdmin::isActive);
        if (admin.isEmpty()) {
            cookies.clear(response);
            throw new BadCredentialsException(SESSION_FAILURE);
        }
        issueSession(admin.get(), response, false);
        return toResponse(admin.get());
    }

    public void logout(HttpServletResponse response) {
        cookies.clear(response);
    }

    private void issueSession(PlatformAdmin admin, HttpServletResponse response, boolean withRefresh) {
        cookies.setAccess(response, jwt.issueAccess(admin.getId(), admin.getEmail(), admin.getFullName()));
        if (withRefresh) {
            cookies.setRefresh(response, jwt.issueRefresh(admin.getId(), admin.getEmail(), admin.getFullName()));
        }
    }

    private static PlatformAuthResponse toResponse(PlatformAdmin admin) {
        return new PlatformAuthResponse(admin.getEmail(), admin.getFullName(), PlatformRole.SUPER_ADMIN.name());
    }
}
