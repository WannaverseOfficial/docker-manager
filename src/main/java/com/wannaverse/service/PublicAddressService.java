package com.wannaverse.service;

import com.github.dockerjava.api.model.HostConfig;
import com.wannaverse.persistence.DockerHost;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Finds the public addresses of a Docker host: a short-lived curl container on the host's own
 * network asks a public what's-my-IP service (ipify), so the answer is the address the host's
 * traffic leaves from. Only runs when someone asks (the Detect button).
 */
@Service
public class PublicAddressService {
    static final String CURL_IMAGE = "curlimages/curl:8.23.0";

    /** IPv4-only and IPv6-only endpoints of the same service. */
    static final String IPV4_URL = "https://api.ipify.org", IPV6_URL = "https://api6.ipify.org";

    private final DockerHostService hosts;
    private final ToolRunner tools;

    public PublicAddressService(DockerHostService hosts, ToolRunner tools) {
        this.hosts = hosts;
        this.tools = tools;
    }

    public record Addresses(String ipv4, String ipv6) {}

    public Addresses detect(DockerHost host) {
        String script =
                "curl -4 -fsS --max-time 8 "
                        + IPV4_URL
                        + " || true; echo; curl -6 -fsS --max-time 8 "
                        + IPV6_URL
                        + " || true; echo";
        ToolRunner.Result r;
        try {
            r =
                    tools.run(
                            hosts.getDockerAPI(host),
                            new ToolRunner.Spec(
                                    CURL_IMAGE,
                                    List.of(script),
                                    Map.of(),
                                    // The host's network, so IPv6 works where the host has it.
                                    HostConfig.newHostConfig().withNetworkMode("host"),
                                    List.of("sh", "-c"),
                                    40,
                                    false));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not run the check on this host: " + e.getMessage());
        }
        String[] lines = r.stdout() == null ? new String[0] : r.stdout().split("\\R");
        String v4 = null, v6 = null;
        for (String l : lines) {
            String t = l.trim();
            if (t.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") && v4 == null) v4 = t;
            else if (t.contains(":") && t.matches("^[0-9a-fA-F:]+$") && v6 == null)
                v6 = t.toLowerCase();
        }
        if (v4 == null && v6 == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The host couldn't reach "
                            + IPV4_URL
                            + ". Check its internet access, or enter the address yourself.");
        }
        return new Addresses(v4, v6);
    }
}
