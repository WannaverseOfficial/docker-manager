package com.wannaverse.iac.handlers;

import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.IacException;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How Docker Manager runs an app's container, for rollouts and Git repositories. The image comes
 * from the build or the release, so it is not set here. Field names follow the container form.
 */
@Data
@Doc("How the app's container runs (the image comes from the build or release)")
public class AppContainerSpec {
    @Doc("Container name (Git repositories only; a rollout names its containers itself)")
    private String name;

    @Doc("Command arguments. Empty keeps the image's default.")
    private List<String> command = new ArrayList<>();

    @Doc("Entrypoint override. Empty keeps the image's default.")
    private List<String> entrypoint = new ArrayList<>();

    @Doc("Environment variables, as KEY=value")
    private List<String> env = new ArrayList<>();

    @Doc(
            "Published ports (Git repositories only), as \"8080:80\", \"127.0.0.1:8080:80\" or"
                    + " \"53:53/udp\"")
    private List<String> ports = new ArrayList<>();

    @Doc("Volumes and bind mounts, as \"volume:/path\" or \"/host/path:/path:ro\"")
    private List<String> volumes = new ArrayList<>();

    @Doc("Networks to join; the first is the main one. Empty: the default bridge.")
    private List<String> networks = new ArrayList<>();

    @Doc("Restart policy: no, always, unless-stopped (default) or on-failure")
    private String restartPolicy;

    @Doc("Container labels")
    private Map<String, String> labels = new LinkedHashMap<>();

    @Doc("User to run as, such as 1000:1000")
    private String user;

    @Doc("Working directory inside the container")
    private String workingDir;

    @Doc("Run privileged (administrators only; default false)")
    private Boolean privileged;

    @Doc("Linux capabilities to add, such as NET_ADMIN")
    private List<String> capAdd = new ArrayList<>();

    @Doc("Memory limit in MiB (default: none)")
    private Long memoryLimitMb;

    @Doc("CPU limit in cores, such as 1.5 (default: none)")
    private Double cpus;

    /** The container form's representation. */
    public ContainerSpec toContainerSpec() {
        ContainerSpec c = new ContainerSpec();
        c.setName(blankToNull(name));
        c.setCommand(new ArrayList<>(command));
        c.setEntrypoint(new ArrayList<>(entrypoint));
        c.setEnv(new ArrayList<>(env));
        List<ContainerSpec.PortMapping> mapped = new ArrayList<>();
        for (String p : ports) mapped.add(parsePort(p));
        c.setPorts(mapped);
        c.setVolumes(new ArrayList<>(volumes));
        c.setNetworks(new ArrayList<>(networks));
        if (restartPolicy != null && !restartPolicy.isBlank()) {
            c.setRestartPolicy(restartPolicy.trim());
        }
        c.setLabels(new LinkedHashMap<>(labels));
        c.setUser(blankToNull(user));
        c.setWorkingDir(blankToNull(workingDir));
        c.setPrivileged(Boolean.TRUE.equals(privileged));
        c.setCapAdd(new ArrayList<>(capAdd));
        c.setMemoryLimitMb(memoryLimitMb == null ? 0 : memoryLimitMb);
        c.setCpus(cpus == null ? 0 : cpus);
        return c;
    }

    /** For exports: the stored settings as an entry. */
    public static AppContainerSpec from(ContainerSpec c) {
        if (c == null) return null;
        AppContainerSpec s = new AppContainerSpec();
        s.setName(blankToNull(c.getName()));
        s.setCommand(nonNull(c.getCommand()));
        s.setEntrypoint(nonNull(c.getEntrypoint()));
        s.setEnv(nonNull(c.getEnv()));
        List<String> ports = new ArrayList<>();
        if (c.getPorts() != null) {
            for (ContainerSpec.PortMapping p : c.getPorts()) ports.add(formatPort(p));
        }
        s.setPorts(ports);
        s.setVolumes(nonNull(c.getVolumes()));
        s.setNetworks(nonNull(c.getNetworks()));
        s.setRestartPolicy(
                "unless-stopped".equals(c.getRestartPolicy()) ? null : c.getRestartPolicy());
        s.setLabels(c.getLabels() == null ? new LinkedHashMap<>() : c.getLabels());
        s.setUser(blankToNull(c.getUser()));
        s.setWorkingDir(blankToNull(c.getWorkingDir()));
        if (c.isPrivileged()) s.setPrivileged(true);
        s.setCapAdd(nonNull(c.getCapAdd()));
        if (c.getMemoryLimitMb() > 0) s.setMemoryLimitMb(c.getMemoryLimitMb());
        if (c.getCpus() > 0) s.setCpus(c.getCpus());
        return s;
    }

    static ContainerSpec.PortMapping parsePort(String text) {
        if (text == null || text.isBlank()) throw new IacException("ports: empty port");
        String t = text.trim();
        String protocol = "tcp";
        int slash = t.lastIndexOf('/');
        if (slash >= 0) {
            protocol = t.substring(slash + 1).toLowerCase();
            t = t.substring(0, slash);
            if (!protocol.equals("tcp") && !protocol.equals("udp")) {
                throw new IacException("ports: \"" + text + "\": use /tcp or /udp");
            }
        }
        try {
            int c = t.lastIndexOf(':');
            if (c < 0) {
                return new ContainerSpec.PortMapping(null, null, Integer.parseInt(t), protocol);
            }
            int container = Integer.parseInt(t.substring(c + 1));
            String rest = t.substring(0, c);
            int h = rest.lastIndexOf(':');
            String hostIp = null;
            String hostPort = rest;
            if (h >= 0) {
                hostIp = rest.substring(0, h).replace("[", "").replace("]", "");
                hostPort = rest.substring(h + 1);
            }
            return new ContainerSpec.PortMapping(
                    blankToNull(hostIp),
                    hostPort.isBlank() ? null : Integer.parseInt(hostPort),
                    container,
                    protocol);
        } catch (NumberFormatException e) {
            throw new IacException(
                    "ports: \""
                            + text
                            + "\" is not a port; write \"8080:80\", \"127.0.0.1:8080:80\" or"
                            + " \"53:53/udp\"");
        }
    }

    static String formatPort(ContainerSpec.PortMapping p) {
        StringBuilder sb = new StringBuilder();
        if (p.getHostIp() != null && !p.getHostIp().isBlank()) {
            String ip = p.getHostIp();
            sb.append(ip.contains(":") ? "[" + ip + "]" : ip).append(':');
        }
        if (p.getHostPort() != null) sb.append(p.getHostPort()).append(':');
        sb.append(p.getContainerPort());
        if (p.getProtocol() != null && !"tcp".equals(p.getProtocol())) {
            sb.append('/').append(p.getProtocol());
        }
        return sb.toString();
    }

    private static List<String> nonNull(List<String> l) {
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
