package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.wannaverse.persistence.GroupPermissionRepository;
import com.wannaverse.persistence.RefreshTokenRepository;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserGroupRepository;
import com.wannaverse.persistence.UserNotificationPreferenceRepository;
import com.wannaverse.persistence.UserPermissionRepository;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.UserStatusCache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

class UserEmailTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserRepository users;
    private UserService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service =
                new UserService(
                        users,
                        mock(UserGroupRepository.class),
                        mock(UserPermissionRepository.class),
                        mock(GroupPermissionRepository.class),
                        mock(RefreshTokenRepository.class),
                        encoder,
                        mock(PermissionService.class),
                        mock(UserStatusCache.class),
                        mock(UserNotificationPreferenceRepository.class));
        user = new User();
        user.setId("u1");
        user.setUsername("admin");
        user.setEmail("admin@localhost");
        user.setPasswordHash(encoder.encode("correct horse"));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static HttpStatus status(Runnable r) {
        return (HttpStatus) assertThrows(ResponseStatusException.class, r::run).getStatusCode();
    }

    @Test
    void changesTheirOwnEmailWithTheirPassword() {
        service.changeOwnEmail("u1", "  ops@example.com ", "correct horse");
        assertEquals("ops@example.com", user.getEmail());
    }

    @Test
    void needsTheRightPassword() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                status(() -> service.changeOwnEmail("u1", "ops@example.com", "wrong")));
        assertEquals("admin@localhost", user.getEmail());
    }

    @Test
    void anEmptyEmailRemovesIt() {
        service.changeOwnEmail("u1", "", "correct horse");
        assertNull(user.getEmail());
    }

    @Test
    void refusesAnAddressSomeoneElseHas() {
        when(users.existsByEmail("taken@example.com")).thenReturn(true);
        assertEquals(
                HttpStatus.CONFLICT,
                status(() -> service.changeOwnEmail("u1", "taken@example.com", "correct horse")));
    }

    @Test
    void refusesSomethingThatIsNotAnAddress() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                status(() -> service.changeOwnEmail("u1", "not an email", "correct horse")));
    }
}
