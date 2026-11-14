package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.security.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform super-admin auth. login/refresh/logout are permitAll; {@code /me} requires SUPER_ADMIN. */
@RestController
@RequestMapping("/api/v1/platform/auth")
@RequiredArgsConstructor
public class PlatformAuthController {

    private final PlatformAuthService service;

    @PostMapping("/login")
    public ResponseEntity<CustomApiResponse<PlatformAuthResponse>> login(
            @Valid @RequestBody PlatformLoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        return ResponseEntity.ok(CustomApiResponse.success("Login successful",
                service.login(request, ClientIps.rateLimitKey(http), response)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<CustomApiResponse<PlatformAuthResponse>> refresh(HttpServletRequest http, HttpServletResponse response) {
        return ResponseEntity.ok(CustomApiResponse.success(service.refresh(http, ClientIps.rateLimitKey(http), response)));
    }

    @PostMapping("/logout")
    public ResponseEntity<CustomApiResponse<Void>> logout(HttpServletResponse response) {
        service.logout(response);
        return ResponseEntity.ok(CustomApiResponse.success("Logged out", null));
    }

    @GetMapping("/me")
    public ResponseEntity<CustomApiResponse<PlatformAuthResponse>> me(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(CustomApiResponse.success(
                new PlatformAuthResponse(principal.email(), principal.fullName(), "SUPER_ADMIN")));
    }
}
