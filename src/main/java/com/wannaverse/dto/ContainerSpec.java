package com.wannaverse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the UI lets you set on a container. Used to create a container, to show the edit form
 * for an existing one (derived from `docker inspect`), and to recreate it with changes.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContainerSpec {
    private String image;
    private String name;

    /** Command arguments. Null or empty keeps the image default. */
    private List<String> command = new ArrayList<>();

    /** Entrypoint override. Null or empty keeps the image default. */
    private List<String> entrypoint = new ArrayList<>();

    /** "KEY=value" entries. */
    private List<String> env = new ArrayList<>();

    private List<PortMapping> ports = new ArrayList<>();

    /** Docker bind syntax: "volume-name:/path", "/host/path:/path[:ro]". */
    private List<String> volumes = new ArrayList<>();

    /** Networks to join. The first one is the primary network. Empty means the default bridge. */
    private List<String> networks = new ArrayList<>();

    /** "no", "always", "unless-stopped" or "on-failure". */
    private String restartPolicy = "unless-stopped";

    private Map<String, String> labels = new LinkedHashMap<>();
    private String user;
    private String workingDir;
    private boolean privileged;
    private List<String> capAdd = new ArrayList<>();

    /** Memory limit in MiB, 0 for none. */
    private long memoryLimitMb;

    /** CPU limit in cores (e.g. 1.5), 0 for none. */
    private double cpus;

    /** Start the container after creating it. */
    private boolean start = true;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PortMapping {
        /** Empty for all interfaces. */
        private String hostIp;

        /** Null publishes nothing on the host (the port is only exposed). */
        private Integer hostPort;

        private int containerPort;

        /** "tcp" or "udp". */
        private String protocol = "tcp";
    }
}
