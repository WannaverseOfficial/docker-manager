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
import com.wannaverse.persistence.UserPermission;
import com.wannaverse.persistence.UserPermissionRepository;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.ResourceActions;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserGroupRepository groupRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final GroupPermissionRepository groupPermissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;

    public UserService(
            UserRepository userRepository,
            UserGroupRepository groupRepository,
            UserPermissionRepository userPermissionRepository,
            GroupPermissionRepository groupPermissionRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            PermissionService permissionService) {
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

        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(request.getUsername())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
            }
            user.setUsername(request.getUsername());
        }

        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
            }
            user.setEmail(request.getEmail());
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

        return UserResponse.fromEntity(userRepository.save(user));
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

        userPermissionRepository.deleteByUserId(id);
        refreshTokenRepository.revokeAllForUser(id);
        userRepository.delete(user);
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
