package com.wannaverse.controllers;

import com.wannaverse.dto.AuthResponse;
import com.wannaverse.dto.ChangePasswordRequest;
import com.wannaverse.dto.LoginRequest;
import com.wannaverse.dto.RefreshTokenRequest;
import com.wannaverse.dto.UserResponse;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.CurrentUser;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.service.AuthService;
import com.wannaverse.service.JwtService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Sign-in and session endpoints.
 *
 * <p>The refresh token lives in an HttpOnly, SameSite=Strict cookie scoped to /api/auth, so page
 * scripts can never read it. The short-lived access token is returned in the body and kept in
 * memory by the UI. For older API clients, a refresh token sent in the request body is still
 * accepted.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    static final String REFRESH_COOKIE = "dm_refresh";

    private final AuthService authService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthController(
            AuthService authService, UserRepository userRepository, JwtService jwtService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // getRemoteAddr() already honours X-Forwarded-For from trusted (private-network) proxies,
        // see server.forward-headers-strategy. Reading the header directly would let anyone
        // spoof their address and dodge the rate limiter.
        AuthResponse auth = authService.login(request, httpRequest.getRemoteAddr());
        return withCookie(auth, httpRequest, httpResponse);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String token = refreshToken(body, httpRequest);
        if (token == null) {
            // Not signed in on this browser: a normal answer, not an error.
            return ResponseEntity.noContent().build();
        }
        try {
            return withCookie(authService.refresh(token), httpRequest, httpResponse);
        } catch (ResponseStatusException e) {
            clearCookie(httpRequest, httpResponse);
            throw e;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // No access token required: signing out must work even after it has expired.
        String token = refreshToken(body, httpRequest);
        if (token != null) {
            authService.logout(token);
        }
        clearCookie(httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(
            @CurrentUser SecurityContext ctx,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        requireAuth(ctx);
        authService.logoutAll(ctx.getUserId());
        clearCookie(httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(
            @CurrentUser SecurityContext ctx,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        requireAuth(ctx);
        AuthResponse auth = authService.changePassword(ctx.getUserId(), request);
        return withCookie(auth, httpRequest, httpResponse);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@CurrentUser SecurityContext ctx) {
        requireAuth(ctx);
        User user =
                userRepository
                        .findById(ctx.getUserId())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));
        return ResponseEntity.ok(UserResponse.fromEntity(user));
    }

    private static void requireAuth(SecurityContext ctx) {
        if (ctx == null || !ctx.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    private static String refreshToken(RefreshTokenRequest body, HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (REFRESH_COOKIE.equals(c.getName()) && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        if (body != null && body.getRefreshToken() != null && !body.getRefreshToken().isBlank()) {
            return body.getRefreshToken();
        }
        return null;
    }

    private ResponseEntity<AuthResponse> withCookie(
            AuthResponse auth, HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie =
                ResponseCookie.from(REFRESH_COOKIE, auth.getRefreshToken())
                        .httpOnly(true)
                        .secure(request.isSecure())
                        .sameSite("Strict")
                        .path("/api/auth")
                        .maxAge(Duration.ofMillis(jwtService.getRefreshExpirationMs()))
                        .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        auth.setRefreshToken(null);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(auth);
    }

    private static void clearCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie =
                ResponseCookie.from(REFRESH_COOKIE, "")
                        .httpOnly(true)
                        .secure(request.isSecure())
                        .sameSite("Strict")
                        .path("/api/auth")
                        .maxAge(0)
                        .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
