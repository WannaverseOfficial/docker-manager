package com.wannaverse.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateContainerRequest {
    private String imageName;
    private String containerName;
    private List<String> environmentVariables;
    private Map<Integer, Integer> portBindings;
    private List<String> volumeBindings;
    private String networkName;
    private String user;
}
