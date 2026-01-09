package com.wannaverse.controllers;

import com.wannaverse.dto.CreateGroupRequest;
import com.wannaverse.dto.GrantPermissionRequest;
import com.wannaverse.dto.PermissionResponse;
import com.wannaverse.dto.UpdateGroupRequest;
import com.wannaverse.dto.UserGroupResponse;
import com.wannaverse.persistence.GroupPermissionRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.UserService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final UserService userService;
    private final GroupPermissionRepository groupPermissionRepository;

    public GroupController(
            UserService userService, GroupPermissionRepository groupPermissionRepository) {
        this.userService = userService;
        this.groupPermissionRepository = groupPermissionRepository;
    }

    @GetMapping
    @RequirePermission(resource = Resource.USER_GROUPS, action = "list")
    public ResponseEntity<List<UserGroupResponse>> listGroups() {
        return ResponseEntity.ok(userService.listGroups());
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "read")
    public ResponseEntity<UserGroupResponse> getGroup(@PathVariable String id) {
        return ResponseEntity.ok(userService.getGroup(id));
    }

    @PostMapping
    @RequirePermission(resource = Resource.USER_GROUPS, action = "create")
    @Auditable(resource = Resource.USER_GROUPS, action = "create", captureRequestBody = true)
    public ResponseEntity<UserGroupResponse> createGroup(
            @Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createGroup(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "update")
    @Auditable(
            resource = Resource.USER_GROUPS,
            action = "update",
            resourceIdParam = "id",
            captureRequestBody = true)
    public ResponseEntity<UserGroupResponse> updateGroup(
            @PathVariable String id, @Valid @RequestBody UpdateGroupRequest request) {
        return ResponseEntity.ok(userService.updateGroup(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "delete")
    @Auditable(resource = Resource.USER_GROUPS, action = "delete", resourceIdParam = "id")
    public ResponseEntity<Void> deleteGroup(@PathVariable String id) {
        userService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/permissions")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "manage_permissions")
    public ResponseEntity<List<PermissionResponse>> getGroupPermissions(@PathVariable String id) {
        return ResponseEntity.ok(
                groupPermissionRepository.findByGroupId(id).stream()
                        .map(p -> PermissionResponse.fromGroupPermission(p, null))
                        .toList());
    }

    @PostMapping("/{id}/permissions")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "manage_permissions")
    @Auditable(
            resource = Resource.USER_GROUPS,
            action = "grant_permission",
            resourceIdParam = "id",
            captureRequestBody = true)
    public ResponseEntity<Void> grantPermission(
            @PathVariable String id, @Valid @RequestBody GrantPermissionRequest request) {
        userService.grantGroupPermission(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/permissions/{permissionId}")
    @RequirePermission(resource = Resource.USER_GROUPS, action = "manage_permissions")
    @Auditable(
            resource = Resource.USER_GROUPS,
            action = "revoke_permission",
            resourceIdParam = "id")
    public ResponseEntity<Void> revokePermission(
            @PathVariable String id, @PathVariable String permissionId) {
        userService.revokeGroupPermission(id, permissionId);
        return ResponseEntity.noContent().build();
    }
}
