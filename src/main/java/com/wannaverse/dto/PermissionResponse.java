package com.wannaverse.dto;

import com.wannaverse.persistence.GroupPermission;
import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.UserPermission;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionResponse {
    private String id;
    private Resource resource;
    private String action;
    private String scopeHostId;
    private String scopeResourceId;
    private String source;

    public static PermissionResponse fromUserPermission(UserPermission perm) {
        return PermissionResponse.builder()
                .id(perm.getId())
                .resource(perm.getResource())
                .action(perm.getAction())
                .scopeHostId(perm.getScopeHostId())
                .scopeResourceId(perm.getScopeResourceId())
                .source("direct")
                .build();
    }

    public static PermissionResponse fromGroupPermission(GroupPermission perm, String groupName) {
        return PermissionResponse.builder()
                .id(perm.getId())
                .resource(perm.getResource())
                .action(perm.getAction())
                .scopeHostId(perm.getScopeHostId())
                .scopeResourceId(perm.getScopeResourceId())
                .source("group:" + groupName)
                .build();
    }
}
