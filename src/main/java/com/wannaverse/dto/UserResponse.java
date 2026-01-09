package com.wannaverse.dto;

import com.wannaverse.persistence.User;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private String id;
    private String username;
    private String email;
    private boolean admin;
    private boolean enabled;
    private boolean mustChangePassword;
    private Instant createdAt;
    private Instant lastLoginAt;
    private List<String> groupIds;
    private List<String> groupNames;

    public static UserResponse fromEntity(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .admin(user.isAdmin())
                .enabled(user.isEnabled())
                .mustChangePassword(user.isMustChangePassword())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .groupIds(user.getGroups().stream().map(g -> g.getId()).toList())
                .groupNames(user.getGroups().stream().map(g -> g.getName()).toList())
                .build();
    }
}
