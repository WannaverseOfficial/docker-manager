package com.wannaverse.dto;

import com.wannaverse.persistence.StateSnapshot;
import com.wannaverse.persistence.StateSnapshot.SnapshotType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StateSnapshotResponse {
    private String id;
    private String operationId;
    private SnapshotType snapshotType;
    private String resourceId;
    private String resourceName;
    private String inspectData;
    private String composeContent;
    private String environmentVars;
    private String volumeBindings;
    private String portBindings;
    private String networkSettings;
    private String imageName;
    private String imageId;
    private long createdAt;

    public static StateSnapshotResponse fromEntity(
            StateSnapshot entity, String decompressedInspectData) {
        StateSnapshotResponse response = new StateSnapshotResponse();
        response.setId(entity.getId());
        response.setOperationId(entity.getOperation().getId());
        response.setSnapshotType(entity.getSnapshotType());
        response.setResourceId(entity.getResourceId());
        response.setResourceName(entity.getResourceName());
        response.setInspectData(decompressedInspectData);
        response.setComposeContent(entity.getComposeContent());
        response.setEnvironmentVars(entity.getEnvironmentVars());
        response.setVolumeBindings(entity.getVolumeBindings());
        response.setPortBindings(entity.getPortBindings());
        response.setNetworkSettings(entity.getNetworkSettings());
        response.setImageName(entity.getImageName());
        response.setImageId(entity.getImageId());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
