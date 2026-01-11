package com.wannaverse.service;

import com.wannaverse.dto.PermissionResponse;
import com.wannaverse.persistence.GroupPermission;
import com.wannaverse.persistence.GroupPermissionRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserPermission;
import com.wannaverse.persistence.UserPermissionRepository;
import com.wannaverse.persistence.UserRepository;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PermissionService {

    private final UserPermissionRepository userPermissionRepository;
    private final GroupPermissionRepository groupPermissionRepository;
    private final UserRepository userRepository;

    public PermissionService(
            UserPermissionRepository userPermissionRepository,
            GroupPermissionRepository groupPermissionRepository,
            UserRepository userRepository) {
        this.userPermissionRepository = userPermissionRepository;
        this.groupPermissionRepository = groupPermissionRepository;
        this.userRepository = userRepository;
    }

    @Cacheable(
            value = "permissions",
            key = "#userId + '-' + #resource + '-' + #action + '-' + #hostId")
    public boolean hasPermission(String userId, Resource resource, String action, String hostId) {
        return hasPermission(userId, resource, action, hostId, null);
    }

    public boolean hasPermission(
            String userId, Resource resource, String action, String hostId, String resourceId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isAdmin()) {
            return true;
        }

        List<UserPermission> directPerms =
                userPermissionRepository.findMatchingPermissions(userId, resource, action, hostId);
        if (hasMatchingPermission(directPerms, resourceId)) {
            return true;
        }

        List<GroupPermission> groupPerms =
                groupPermissionRepository.findMatchingPermissionsForUser(
                        userId, resource, action, hostId);
        return hasMatchingGroupPermission(groupPerms, resourceId);
    }

    private boolean hasMatchingPermission(List<UserPermission> perms, String resourceId) {
        for (UserPermission perm : perms) {
            if (perm.getScopeResourceId() == null) {
                return true;
            }
            if (resourceId != null && perm.getScopeResourceId().equals(resourceId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMatchingGroupPermission(List<GroupPermission> perms, String resourceId) {
        for (GroupPermission perm : perms) {
            if (perm.getScopeResourceId() == null) {
                return true;
            }
            if (resourceId != null && perm.getScopeResourceId().equals(resourceId)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyPermission(
            String userId, Resource resource, String action, String hostId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isAdmin()) {
            return true;
        }

        List<UserPermission> directPerms =
                userPermissionRepository.findMatchingPermissions(userId, resource, action, hostId);
        if (!directPerms.isEmpty()) {
            return true;
        }

        List<GroupPermission> groupPerms =
                groupPermissionRepository.findMatchingPermissionsForUser(
                        userId, resource, action, hostId);
        return !groupPerms.isEmpty();
    }

    @Transactional(readOnly = true)
    public Set<String> getAllowedResourceIds(
            String userId, Resource resource, String action, String hostId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isAdmin()) {
            return null;
        }

        Set<String> allowedIds = new HashSet<>();
        boolean hasGlobalAccess = false;

        List<UserPermission> directPerms =
                userPermissionRepository.findMatchingPermissions(userId, resource, action, hostId);
        for (UserPermission perm : directPerms) {
            if (perm.getScopeResourceId() == null) {
                hasGlobalAccess = true;
                break;
            }
            allowedIds.add(perm.getScopeResourceId());
        }

        if (hasGlobalAccess) {
            return null;
        }

        List<GroupPermission> groupPerms =
                groupPermissionRepository.findMatchingPermissionsForUser(
                        userId, resource, action, hostId);
        for (GroupPermission perm : groupPerms) {
            if (perm.getScopeResourceId() == null) {
                return null;
            }
            allowedIds.add(perm.getScopeResourceId());
        }

        return allowedIds;
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> getEffectivePermissions(String userId) {
        List<PermissionResponse> result = new ArrayList<>();

        List<UserPermission> directPerms = userPermissionRepository.findByUserId(userId);
        for (UserPermission perm : directPerms) {
            result.add(PermissionResponse.fromUserPermission(perm));
        }

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new RuntimeException("User not found"));
        for (var group : user.getGroups()) {
            for (var perm : group.getPermissions()) {
                result.add(PermissionResponse.fromGroupPermission(perm, group.getName()));
            }
        }

        return result;
    }

    @CacheEvict(value = "permissions", allEntries = true)
    public void evictPermissionCache() {}
}
