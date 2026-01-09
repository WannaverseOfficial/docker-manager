package com.wannaverse.service;

import com.wannaverse.dto.AuthResponse;
import com.wannaverse.dto.ChangePasswordRequest;
import com.wannaverse.dto.LoginRequest;
import com.wannaverse.persistence.RefreshToken;
import com.wannaverse.persistence.RefreshTokenRepository;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserRepository;

import io.jsonwebtoken.Claims;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            NotificationService notificationService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        User user =
                userRepository
                        .findByUsername(request.getUsername())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        // Send login notification
        notificationService.notifyUserLogin(user, ipAddress);

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

    @Transactional
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
    public void changePassword(String userId, ChangePasswordRequest request) {
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

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Send password changed notification
        notificationService.notifyPasswordChanged(user);

        refreshTokenRepository.revokeAllForUser(userId);
    }
}
