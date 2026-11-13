package com.wannaverse.service;

import com.wannaverse.dto.CreateGroupRequest;
import com.wannaverse.dto.CreateUserRequest;
import com.wannaverse.dto.GrantPermissionRequest;
import com.wannaverse.dto.PasswordResetResponse;
import com.wannaverse.dto.UpdateGroupRequest;
import com.wannaverse.dto.UpdateUserRequest;
import com.wannaverse.dto.UserGroupResponse;
import com.wannaverse.dto.UserResponse;
import com.wannaverse.persistence.GroupPermission;
import com.wannaverse.persistence.GroupPermissionRepository;
import com.wannaverse.persistence.RefreshTokenRepository;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserGroup;
import com.wannaverse.persistence.UserGroupRepository;
import com.wannaverse.persistence.UserNotificationPreferenceRepository;
import com.wannaverse.persistence.UserPermission;
import com.wannaverse.persistence.UserPermissionRepository;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.ResourceActions;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.security.UserStatusCache;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserGroupRepository groupRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final GroupPermissionRepository groupPermissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final UserStatusCache userStatusCache;
    private final UserNotificationPreferenceRepository preferenceRepository;

    public UserService(
            UserRepository userRepository,
            UserGroupRepository groupRepository,
            UserPermissionRepository userPermissionRepository,
            GroupPermissionRepository groupPermissionRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            PermissionService permissionService,
            UserStatusCache userStatusCache,
            UserNotificationPreferenceRepository preferenceRepository) {
        this.userStatusCache = userStatusCache;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.groupPermissionRepository = groupPermissionRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream().map(UserResponse::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(String id) {
        User user =
                userRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));
        return UserResponse.fromEntity(user);
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request, String tempPassword) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        if (request.getEmail() != null && userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setAdmin(request.isAdmin());
        user.setMustChangePassword(true);
        user.setEnabled(true);

        return UserResponse.fromEntity(userRepository.save(user));
    }

    /**
     * Sets a user's email address, or removes it when blank. Addresses are unique, so two people
     * never share one account's notifications.
     */
    void setEmail(User user, String email) {
        String value = email == null || email.isBlank() ? null : email.trim();
        if (value != null && !EMAIL.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email format");
        }
        if (Objects.equals(value, user.getEmail())) return;
        if (value != null && userRepository.existsByEmail(value)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        user.setEmail(value);
    }

    /** A user changes their own email, confirming it with their password. */
    @Transactional
    public UserResponse changeOwnEmail(String userId, String email, String currentPassword) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));
        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is incorrect");
        }
        setEmail(user, email);
        return UserResponse.fromEntity(userRepository.save(user));
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    public String generateTempPassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public UserResponse updateUser(String id, UpdateUserRequest request) {
        User user =
                userRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));

        String username = request.getUsername() == null ? null : request.getUsername().trim();
        if (username != null && !username.equals(user.getUsername())) {
            if (username.length() < 3) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Username must be between 3 and 100 characters");
            }
            if (userRepository.existsByUsername(username)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
            }
            user.setUsername(username);
        }

        // An empty email removes the address.
        if (request.getEmail() != null) {
            setEmail(user, request.getEmail());
        }

        boolean self = id.equals(SecurityContextHolder.getCurrentUserId());
        boolean removesAdmin =
                user.isAdmin()
                        && ((request.getAdmin() != null && !request.getAdmin())
                                || (request.getEnabled() != null && !request.getEnabled()));
        if (removesAdmin && userRepository.countByIsAdminTrue() <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This is the only administrator. Make someone else an administrator first.");
        }
        if (self && request.getEnabled() != null && !request.getEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "You can't disable your own account");
        }

        if (request.getAdmin() != null) {
            user.setAdmin(request.getAdmin());
        }

        if (request.getEnabled() != null) {
            user.setEnabled(request.getEnabled());
            if (!request.getEnabled()) {
                refreshTokenRepository.revokeAllForUser(id);
            }
        }

        UserResponse saved = UserResponse.fromEntity(userRepository.save(user));
        userStatusCache.evict(id);
        return saved;
    }

    @Transactional
    public void deleteUser(String id) {
        User user =
                userRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));

        if (id.equals(SecurityContextHolder.getCurrentUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "You can't delete your own account");
        }
        if (user.isAdmin() && userRepository.countByIsAdminTrue() <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "You can't delete the only administrator");
        }

        userPermissionRepository.deleteByUserId(id);
        preferenceRepository.deleteByUserId(id);
        refreshTokenRepository.revokeAllForUser(id);
        user.getGroups().clear();
        userRepository.delete(user);
        userStatusCache.evict(id);
    }

    @Transactional
    public PasswordResetResponse resetPassword(String userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));

        String tempPassword = generateTempPassword();
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);

        refreshTokenRepository.revokeAllForUser(userId);
        userStatusCache.evict(userId);

        return new PasswordResetResponse(tempPassword);
    }

    @Transactional
    public void grantUserPermission(String userId, GrantPermissionRequest request) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));

        if (!ResourceActions.isValidAction(request.getResource(), request.getAction())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid action '"
                            + request.getAction()
                            + "' for resource "
                            + request.getResource());
        }

        var existing =
                userPermissionRepository.findExactPermission(
                        userId,
                        request.getResource(),
                        request.getAction(),
                        request.getScopeHostId(),
                        request.getScopeResourceId());
        if (existing.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Permission already exists");
        }

        UserPermission perm = new UserPermission();
        perm.setUser(user);
        perm.setResource(request.getResource());
        perm.setAction(request.getAction());
        perm.setScopeHostId(request.getScopeHostId());
        perm.setScopeResourceId(request.getScopeResourceId());
        userPermissionRepository.save(perm);

        permissionService.evictPermissionCache();
    }

    @Transactional
    public void revokeUserPermission(String userId, String permissionId) {
        UserPermission perm =
                userPermissionRepository
                        .findById(permissionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Permission not found"));

        if (!perm.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Permission does not belong to this user");
        }

        userPermissionRepository.delete(perm);
        permissionService.evictPermissionCache();
    }

    @Transactional
    public void addUserToGroup(String userId, String groupId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));
        UserGroup group =
                groupRepository
                        .findById(groupId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Group not found"));

        user.getGroups().add(group);
        userRepository.save(user);
        permissionService.evictPermissionCache();
    }

    @Transactional
    public void removeUserFromGroup(String userId, String groupId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));

        user.getGroups().removeIf(g -> g.getId().equals(groupId));
        userRepository.save(user);
        permissionService.evictPermissionCache();
    }

    @Transactional(readOnly = true)
    public List<UserGroupResponse> listGroups() {
        return groupRepository.findAll().stream().map(UserGroupResponse::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public UserGroupResponse getGroup(String id) {
        UserGroup group =
                groupRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Group not found"));
        return UserGroupResponse.fromEntity(group);
    }

    @Transactional
    public UserGroupResponse createGroup(CreateGroupRequest request) {
        if (groupRepository.existsByName(request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group name already exists");
        }

        UserGroup group = new UserGroup();
        group.setName(request.getName());
        group.setDescription(request.getDescription());

        return UserGroupResponse.fromEntity(groupRepository.save(group));
    }

    @Transactional
    public UserGroupResponse updateGroup(String id, UpdateGroupRequest request) {
        UserGroup group =
                groupRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Group not found"));

        if (request.getName() != null && !request.getName().equals(group.getName())) {
            if (groupRepository.existsByName(request.getName())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Group name already exists");
            }
            group.setName(request.getName());
        }

        if (request.getDescription() != null) {
            group.setDescription(request.getDescription());
        }

        return UserGroupResponse.fromEntity(groupRepository.save(group));
    }

    @Transactional
    public void deleteGroup(String id) {
        UserGroup group =
                groupRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Group not found"));

        for (User member : group.getMembers()) {
            member.getGroups().remove(group);
        }

        groupPermissionRepository.deleteByGroupId(id);
        groupRepository.delete(group);
        permissionService.evictPermissionCache();
    }

    @Transactional
    public void grantGroupPermission(String groupId, GrantPermissionRequest request) {
        UserGroup group =
                groupRepository
                        .findById(groupId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Group not found"));

        if (!ResourceActions.isValidAction(request.getResource(), request.getAction())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid action '"
                            + request.getAction()
                            + "' for resource "
                            + request.getResource());
        }

        var existing =
                groupPermissionRepository.findExactPermission(
                        groupId,
                        request.getResource(),
                        request.getAction(),
                        request.getScopeHostId(),
                        request.getScopeResourceId());
        if (existing.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Permission already exists");
        }

        GroupPermission perm = new GroupPermission();
        perm.setGroup(group);
        perm.setResource(request.getResource());
        perm.setAction(request.getAction());
        perm.setScopeHostId(request.getScopeHostId());
        perm.setScopeResourceId(request.getScopeResourceId());
        groupPermissionRepository.save(perm);

        permissionService.evictPermissionCache();
    }

    @Transactional
    public void revokeGroupPermission(String groupId, String permissionId) {
        GroupPermission perm =
                groupPermissionRepository
                        .findById(permissionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Permission not found"));

        if (!perm.getGroup().getId().equals(groupId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Permission does not belong to this group");
        }

        groupPermissionRepository.delete(perm);
        permissionService.evictPermissionCache();
    }
}
