package com.wannaverse.controllers;

import com.wannaverse.dto.CreateUserRequest;
import com.wannaverse.dto.GrantPermissionRequest;
import com.wannaverse.dto.PasswordResetResponse;
import com.wannaverse.dto.PermissionResponse;
import com.wannaverse.dto.UpdateUserRequest;
import com.wannaverse.dto.UserResponse;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.PermissionService;
import com.wannaverse.service.UserService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final PermissionService permissionService;

    public UserController(UserService userService, PermissionService permissionService) {
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @GetMapping
    @RequirePermission(resource = Resource.USERS, action = "list")
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.USERS, action = "read")
    public ResponseEntity<UserResponse> getUser(@PathVariable String id) {
        return ResponseEntity.ok(userService.getUser(id));
    }

    @PostMapping
    @RequirePermission(resource = Resource.USERS, action = "create")
    @Auditable(resource = Resource.USERS, action = "create", captureRequestBody = true)
    public ResponseEntity<Map<String, Object>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        String tempPassword = userService.generateTempPassword();
        UserResponse user = userService.createUser(request, tempPassword);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("user", user, "temporaryPassword", tempPassword));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = Resource.USERS, action = "update")
    @Auditable(
            resource = Resource.USERS,
            action = "update",
            resourceIdParam = "id",
            captureRequestBody = true)
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = Resource.USERS, action = "delete")
    @Auditable(resource = Resource.USERS, action = "delete", resourceIdParam = "id")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    @RequirePermission(resource = Resource.USERS, action = "reset_password")
    @Auditable(resource = Resource.USERS, action = "reset_password", resourceIdParam = "id")
    public ResponseEntity<PasswordResetResponse> resetPassword(@PathVariable String id) {
        return ResponseEntity.ok(userService.resetPassword(id));
    }

    @GetMapping("/{id}/permissions")
    @RequirePermission(resource = Resource.USERS, action = "manage_permissions")
    public ResponseEntity<List<PermissionResponse>> getUserPermissions(@PathVariable String id) {
        return ResponseEntity.ok(permissionService.getEffectivePermissions(id));
    }

    @PostMapping("/{id}/permissions")
    @RequirePermission(resource = Resource.USERS, action = "manage_permissions")
    @Auditable(
            resource = Resource.USERS,
            action = "grant_permission",
            resourceIdParam = "id",
            captureRequestBody = true)
    public ResponseEntity<Void> grantPermission(
            @PathVariable String id, @Valid @RequestBody GrantPermissionRequest request) {
        userService.grantUserPermission(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/permissions/{permissionId}")
    @RequirePermission(resource = Resource.USERS, action = "manage_permissions")
    @Auditable(resource = Resource.USERS, action = "revoke_permission", resourceIdParam = "id")
    public ResponseEntity<Void> revokePermission(
            @PathVariable String id, @PathVariable String permissionId) {
        userService.revokeUserPermission(id, permissionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/groups/{groupId}")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "manage_members")
    @Auditable(resource = Resource.USER_GROUPS, action = "add_member", resourceIdParam = "groupId")
    public ResponseEntity<Void> addToGroup(@PathVariable String id, @PathVariable String groupId) {
        userService.addUserToGroup(id, groupId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/groups/{groupId}")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "manage_members")
    @Auditable(
            resource = Resource.USER_GROUPS,
            action = "remove_member",
            resourceIdParam = "groupId")
    public ResponseEntity<Void> removeFromGroup(
            @PathVariable String id, @PathVariable String groupId) {
        userService.removeUserFromGroup(id, groupId);
        return ResponseEntity.noContent().build();
    }
}
