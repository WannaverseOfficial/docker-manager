package com.wannaverse.dto;

import com.wannaverse.persistence.UserGroup;

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
public class UserGroupResponse {
    private String id;
    private String name;
    private String description;
    private Instant createdAt;
    private int memberCount;
    private List<String> memberIds;

    public static UserGroupResponse fromEntity(UserGroup group) {
        return UserGroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .createdAt(group.getCreatedAt())
                .memberCount(group.getMembers().size())
                .memberIds(group.getMembers().stream().map(u -> u.getId()).toList())
                .build();
    }
}
