package com.wannaverse.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.*;

/**
 * Reads a compose file to (1) reject malformed files early with a clear message, (2) list the
 * images it uses so image policies apply to stacks too, and (3) stop non-administrators from using
 * settings that give a container control of the host. Those settings are the same ones the
 * container form restricts: privileged mode, added capabilities, devices, host namespaces and
 * bind-mounting host folders (including the Docker socket).
 */
@Component
public class ComposeInspector {

    public record Summary(List<String> services, List<String> images) {}

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String content) {
        if (content == null || content.isBlank()) {
            throw bad("The compose file is empty");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(2 * 1024 * 1024);
        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(content);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            throw bad("The compose file is not valid YAML: " + msg.lines().findFirst().orElse(""));
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw bad("The compose file must be a YAML mapping with a 'services' section");
        }
        Object services = map.get("services");
        if (!(services instanceof Map<?, ?> s) || s.isEmpty()) {
            throw bad("The compose file needs a 'services' section with at least one service");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    public Summary summarize(String content) {
        Map<String, Object> root = parse(content);
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        List<String> images = new ArrayList<>();
        for (Object svc : services.values()) {
            if (svc instanceof Map<?, ?> m && m.get("image") instanceof String image) {
                images.add(image);
            }
        }
        return new Summary(new ArrayList<>(services.keySet()), images);
    }

    /** Throws 403 when a non-administrator's compose file could take over the host. */
    @SuppressWarnings("unchecked")
    public void checkAllowedForNonAdmin(String content) {
        Map<String, Object> root = parse(content);
        Map<String, Object> services = (Map<String, Object>) root.get("services");

        for (var entry : services.entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> svc = (Map<String, Object>) raw;

            if (Boolean.TRUE.equals(svc.get("privileged"))) deny(name, "privileged mode");
            if (svc.containsKey("cap_add")) deny(name, "cap_add");
            if (svc.containsKey("devices")) deny(name, "devices");
            if (svc.containsKey("security_opt")) deny(name, "security_opt");
            for (String ns : List.of("network_mode", "pid", "ipc", "userns_mode", "uts")) {
                Object v = svc.get(ns);
                if (v instanceof String str
                        && (str.equals("host") || str.startsWith("container:"))) {
                    deny(name, ns + ": " + str);
                }
            }
            Object volumes = svc.get("volumes");
            if (volumes instanceof List<?> list) {
                for (Object v : list) {
                    if (v instanceof String str) {
                        String source = str.split(":", 2)[0];
                        if (str.contains(":") && isHostPath(source)) {
                            deny(name, "mounting the host folder '" + source + "'");
                        }
                    } else if (v instanceof Map<?, ?> m) {
                        if ("bind".equals(m.get("type"))) deny(name, "bind mounts");
                    }
                }
            }
        }

        Object topVolumes = root.get("volumes");
        if (topVolumes instanceof Map<?, ?> vols) {
            for (var v : vols.entrySet()) {
                if (v.getValue() instanceof Map<?, ?> cfg && cfg.containsKey("driver_opts")) {
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN,
                            "Only administrators can set driver_opts on volumes (volume '"
                                    + v.getKey()
                                    + "')");
                }
            }
        }
    }

    private static boolean isHostPath(String source) {
        return source.startsWith("/")
                || source.startsWith(".")
                || source.startsWith("~")
                || source.startsWith("$")
                || source.contains("\\");
    }

    private static void deny(String service, String what) {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only administrators can use " + what + " (service '" + service + "')");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
