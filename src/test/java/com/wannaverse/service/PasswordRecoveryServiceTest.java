package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.wannaverse.dto.PasswordResetResponse;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.UserStatusCache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

class PasswordRecoveryServiceTest {
    @TempDir Path data;
    private UserRepository users;
    private UserService userService;
    private User admin;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        userService = mock(UserService.class);
        admin = new User();
        admin.setId("u1");
        admin.setUsername("admin");
        admin.setEnabled(true);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(users.findById("u1")).thenReturn(Optional.of(admin));
        when(userService.resetPassword("u1")).thenReturn(new PasswordResetResponse("temp"));
    }

    private PasswordRecoveryService service(String username) {
        return new PasswordRecoveryService(
                users, userService, mock(UserStatusCache.class), username, data.toString());
    }

    private Path marker() {
        return data.resolve(PasswordRecoveryService.MARKER);
    }

    @Test
    void resetsTheNamedUserAndRemembersIt() throws Exception {
        service("admin").resetIfAsked();
        verify(userService).resetPassword("u1");
        assertEquals("admin", Files.readString(marker()).trim());
    }

    @Test
    void aForgottenVariableDoesNotResetAgainOnceTheyChoseAPassword() throws Exception {
        Files.writeString(marker(), "admin\n");
        admin.setMustChangePassword(false);
        service("admin").resetIfAsked();
        verify(userService, never()).resetPassword(any());
    }

    @Test
    void resetsAgainWhileTheTemporaryPasswordIsUnused() throws Exception {
        Files.writeString(marker(), "admin\n");
        admin.setMustChangePassword(true);
        service("admin").resetIfAsked();
        verify(userService).resetPassword("u1");
    }

    @Test
    void reEnablesADisabledAccount() {
        admin.setEnabled(false);
        service("admin").resetIfAsked();
        assertTrue(admin.isEnabled());
        verify(users).save(admin);
    }

    @Test
    void withoutTheVariableNothingIsResetAndTheMarkerGoes() throws Exception {
        Files.writeString(marker(), "admin\n");
        service("  ").resetIfAsked();
        verify(userService, never()).resetPassword(any());
        assertFalse(Files.exists(marker()));
    }

    @Test
    void anUnknownUserIsLeftAlone() {
        service("nobody").resetIfAsked();
        verify(userService, never()).resetPassword(any());
        assertFalse(Files.exists(marker()));
    }
}
