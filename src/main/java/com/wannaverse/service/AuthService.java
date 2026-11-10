package com.wannaverse.service;

import com.wannaverse.dto.AuthResponse;
import com.wannaverse.dto.ChangePasswordRequest;
import com.wannaverse.dto.LoginRequest;
import com.wannaverse.persistence.RefreshToken;
import com.wannaverse.persistence.RefreshTokenRepository;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.LoginRateLimiter;

import io.jsonwebtoken.Claims;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class AuthService {
    private static final long REFRESH_GRACE_SECONDS = 20;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final LoginRateLimiter rateLimiter;
    private final com.wannaverse.security.UserStatusCache userStatusCache;

    /** Compared against when the username does not exist, so both paths take the same time. */
    private volatile String dummyHash;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            NotificationService notificationService,
            LoginRateLimiter rateLimiter,
            com.wannaverse.security.UserStatusCache userStatusCache) {
        this.userStatusCache = userStatusCache;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
        this.rateLimiter = rateLimiter;
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode("not-a-real-password-" + System.nanoTime());
        }
        return dummyHash;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        long wait = rateLimiter.retryAfterMs(ipAddress, request.getUsername());
        if (wait > 0) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-in attempts. Try again in "
                            + Math.max(1, (wait + 999) / 1000)
                            + " seconds.");
        }

        User user = userRepository.findByUsername(request.getUsername()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash();
        boolean passwordOk = passwordEncoder.matches(request.getPassword(), hash);

        if (user == null || !passwordOk) {
            rateLimiter.recordFailure(ipAddress, request.getUsername());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }

        rateLimiter.recordSuccess(ipAddress, request.getUsername());

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        // Send login notification
        notificationService.notifyUserLogin(user, ipAddress);

        return issueSession(user);
    }

    /** Starts a new session (new refresh-token family) for the user. */
    private AuthResponse issueSession(User user) {
        String accessToken =
                jwtService.generateAccessToken(
                        user.getId(),
                        user.getUsername(),
                        user.isAdmin(),
                        user.isMustChangePassword());

        String family = jwtService.generateTokenFamily();
        String refreshToken = jwtService.generateRefreshToken(user.getId(), family);

        RefreshToken refreshTokenEntity = new RefreshToken();
        refreshTokenEntity.setTokenHash(jwtService.hashToken(refreshToken));
        refreshTokenEntity.setUser(user);
        refreshTokenEntity.setFamily(family);
        refreshTokenEntity.setExpiresAt(
                Instant.now().plusMillis(jwtService.getRefreshExpirationMs()));
        refreshTokenRepository.save(refreshTokenEntity);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .username(user.getUsername())
                .admin(user.isAdmin())
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }

    // Reuse revokes every session in the family, so that change must commit even though the
    // request then fails.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtService.parseRefreshToken(refreshToken);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String tokenHash = jwtService.hashToken(refreshToken);
        RefreshToken stored =
                refreshTokenRepository
                        .findByTokenHash(tokenHash)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED,
                                                "Refresh token not found"));

        if (stored.isRevoked()) {
            // Two tabs refreshing with the same cookie at the same moment is not theft: the
            // other tab just rotated it. Ask the client to retry with the new cookie.
            if (refreshTokenRepository.existsByFamilyAndRevokedFalseAndCreatedAtAfter(
                    stored.getFamily(), Instant.now().minusSeconds(REFRESH_GRACE_SECONDS))) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Session was just refreshed. Retry.");
            }
            refreshTokenRepository.revokeFamily(stored.getFamily());
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Token reuse detected - all sessions revoked");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        User user = stored.getUser();
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }

        String newAccessToken =
                jwtService.generateAccessToken(
                        user.getId(),
                        user.getUsername(),
                        user.isAdmin(),
                        user.isMustChangePassword());

        String newRefreshToken = jwtService.generateRefreshToken(user.getId(), stored.getFamily());

        RefreshToken newRefreshTokenEntity = new RefreshToken();
        newRefreshTokenEntity.setTokenHash(jwtService.hashToken(newRefreshToken));
        newRefreshTokenEntity.setUser(user);
        newRefreshTokenEntity.setFamily(stored.getFamily());
        newRefreshTokenEntity.setExpiresAt(
                Instant.now().plusMillis(jwtService.getRefreshExpirationMs()));
        refreshTokenRepository.save(newRefreshTokenEntity);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .userId(user.getId())
                .username(user.getUsername())
                .admin(user.isAdmin())
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }

    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = jwtService.hashToken(refreshToken);
        refreshTokenRepository
                .findByTokenHash(tokenHash)
                .ifPresent(
                        token -> {
                            token.setRevoked(true);
                            refreshTokenRepository.save(token);
                        });
    }

    @Transactional
    public void logoutAll(String userId) {
        refreshTokenRepository.revokeAllForUser(userId);
    }

    @Transactional
    public AuthResponse changePassword(String userId, ChangePasswordRequest request) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The new password must be different from the current one");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Send password changed notification
        notificationService.notifyPasswordChanged(user);

        // Sign out every other session, then give this one fresh tokens.
        refreshTokenRepository.revokeAllForUser(userId);
        userStatusCache.evict(userId);
        return issueSession(user);
    }
}
