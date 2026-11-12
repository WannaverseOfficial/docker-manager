package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.IacJson;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.persistence.ObservabilityStack;
import com.wannaverse.persistence.ObservabilityStackRepository;
import com.wannaverse.service.ObservabilityService;

import lombok.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code observability}: the per-host Grafana, Prometheus, Mimir, Loki and Tempo stack, keyed by
 * the host. Setting it up runs in the background; applying waits until it is done (up to 30
 * minutes, the images are large) and reports its outcome.
 */
@Component
public class ObservabilityHandler implements ResourceHandler<ObservabilityHandler.StackSpec> {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityHandler.class);
    private static final long WAIT_MILLIS = 30 * 60 * 1000L;

    public enum Access {
        /** On a port of the host (127.0.0.1 is not enforced; firewall it as needed). */
        LOCAL,
        /** Through Ingress at a hostname, signed in with Docker Manager accounts. */
        PUBLIC
    }

    @Data
    @Doc("The observability stack of the host named by the entry's key")
    public static class StackSpec {
        @Doc("Keep metrics long-term in Mimir (default true)")
        private Boolean mimir;

        @Doc("Collect logs in Loki (default true)")
        private Boolean loki;

        @Doc("Collect traces in Tempo (default true)")
        private Boolean tempo;

        @Doc("Collect every container's logs (default true; needs loki)")
        private Boolean collectLogs;

        @Doc("Collect host and container metrics (default true)")
        private Boolean collectMetrics;

        @Doc("Accept OpenTelemetry from apps (default true)")
        private Boolean otlp;

        @Doc("Days of metrics to keep, 1-3650 (default 30)")
        private Integer metricsRetentionDays;

        @Doc("Days of logs to keep, 1-3650 (default 14)")
        private Integer logsRetentionDays;

        @Doc("Days of traces to keep, 1-365 (default 7)")
        private Integer tracesRetentionDays;

        @Doc(
                "How Grafana is reached: local (a port on the host, default) or public (through"
                        + " Ingress at hostname, which must be turned on for the host)")
        private Access access;

        @Doc("Grafana's full hostname, for public access, such as grafana.example.com")
        private String hostname;

        @Doc("Grafana's port on the host, for local access (default 3000)")
        private Integer port;

        @Doc(
                "The address people open Docker Manager at, for signing in to Grafana with public"
                        + " access. Default: BASE_URL.")
        private String dockerManagerUrl;

        @Doc(
                "Containers (names) connected to the OpenTelemetry network, so they can send to"
                        + " dm-obs-alloy:4317. Containers not listed are left connected.")
        private List<String> containers = new ArrayList<>();

        @Doc(
                "When the entry is removed from the file: keep (default; it keeps running, no"
                        + " longer managed), delete (remove the containers, keep the collected"
                        + " data volumes) or delete-data (remove everything). Takes effect once"
                        + " applied, so apply it before removing the entry.")
        private DatabasesHandler.OnRemove onRemove;
    }

    private final ObservabilityStackRepository stacks;
    private final ObservabilityService observability;
    private final DockerHostRepository hosts;
    private final String baseUrl;

    private final com.wannaverse.service.DockerHostService dockerHosts;

    public ObservabilityHandler(
            ObservabilityStackRepository stacks,
            ObservabilityService observability,
            DockerHostRepository hosts,
            @Value("${app.base-url:}") String baseUrl,
            com.wannaverse.service.DockerHostService dockerHosts) {
        this.dockerHosts = dockerHosts;
        this.stacks = stacks;
        this.observability = observability;
        this.hosts = hosts;
        this.baseUrl = baseUrl;
    }

    @Override
    public String section() {
        return "observability";
    }

    @Override
    public int order() {
        return 240;
    }

    @Override
    public Class<StackSpec> specType() {
        return StackSpec.class;
    }

    @Override
    public String description() {
        return "Grafana, Prometheus, Mimir, Loki and Tempo per host, keyed by the host";
    }

    @Override
    public void validate(Entry<StackSpec> entry, ApplyContext ctx) {
        ctx.hostId(entry.localKey());
        StackSpec s = entry.spec();
        days("metricsRetentionDays", s.getMetricsRetentionDays(), 3650);
        days("logsRetentionDays", s.getLogsRetentionDays(), 3650);
        days("tracesRetentionDays", s.getTracesRetentionDays(), 365);
        if (s.getAccess() == Access.PUBLIC) {
            if (s.getHostname() == null || !s.getHostname().contains(".")) {
                throw new IacException("hostname: give Grafana's full hostname for public access");
            }
            if (s.getPort() != null) throw new IacException("port: only used with local access");
        } else {
            if (s.getHostname() != null) {
                throw new IacException("hostname: only used with access: public");
            }
            if (s.getPort() != null && (s.getPort() < 1024 || s.getPort() > 65535)) {
                throw new IacException("port: choose a port between 1024 and 65535");
            }
        }
        if (Boolean.FALSE.equals(s.getLoki()) && Boolean.TRUE.equals(s.getCollectLogs())) {
            throw new IacException("collectLogs: needs loki");
        }
        if (Boolean.FALSE.equals(s.getOtlp()) && !s.getContainers().isEmpty()) {
            throw new IacException("containers: needs otlp");
        }
    }

    private static void days(String field, Integer days, int max) {
        if (days != null && (days < 1 || days > max)) {
            throw new IacException(field + ": between 1 and " + max);
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<StackSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.localKey());
        if (hostId == null) return Optional.empty();
        return stacks.findByDockerHostId(hostId).map(s -> target(s, entry.localKey()));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return record.getHostId() != null
                && stacks.findByDockerHostId(record.getHostId()).isPresent();
    }

    @Override
    public boolean isManaged(String targetId, String hostId, ManagedResourceRepository managed) {
        // Requests name the host, not the stack.
        String host = hostId != null ? hostId : targetId;
        return host != null
                && managed.findBySection(section()).stream()
                        .anyMatch(r -> host.equals(r.getHostId()));
    }

    @Override
    public Target create(Entry<StackSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.localKey());
        observability.create(hostId, settings(entry.spec()));
        waitUntilDone(hostId);
        connect(hostId, entry.spec(), ctx.settling());
        return target(require(hostId), entry.localKey());
    }

    @Override
    public Target update(
            Entry<StackSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.localKey());
        require(hostId);
        waitUntilIdle(hostId);
        Set<String> settingsFields = new HashSet<>(changes.fields());
        settingsFields.removeAll(List.of("containers", "onRemove"));
        if (changes.takeover() || !settingsFields.isEmpty()) {
            observability.update(hostId, settings(entry.spec()));
            waitUntilDone(hostId);
        }
        if (changes.has("containers", "otlp")) connect(hostId, entry.spec(), ctx.settling());
        return target(require(hostId), entry.localKey());
    }

    /** Listed containers that are not on the OpenTelemetry network (re-created, or made later). */
    @Override
    public Set<String> drift(Entry<StackSpec> entry, ManagedResource record, ApplyContext ctx) {
        List<String> wanted = entry.spec().getContainers();
        if (wanted.isEmpty() || Boolean.FALSE.equals(entry.spec().getOtlp())) return Set.of();
        if (record.getHostId() == null
                || stacks.findByDockerHostId(record.getHostId()).isEmpty()
                || observability.isBusy(record.getHostId())) {
            return Set.of();
        }
        Set<String> members = new HashSet<>();
        for (ObservabilityService.Member m : observability.members(record.getHostId())) {
            members.add(m.name());
        }
        return DockerEntries.memberDrift(
                dockerHosts.getDockerAPI(record.getHostId()), wanted, members);
    }

    private void connect(String hostId, StackSpec s, boolean settling) {
        if (s.getContainers().isEmpty()) return;
        Set<String> members = new HashSet<>();
        for (ObservabilityService.Member m : observability.members(hostId)) members.add(m.name());
        for (String c : s.getContainers()) {
            if (members.contains(c)) continue;
            try {
                observability.connect(hostId, c);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                // A later section may create it; the check after the apply connects it then.
                if (e.getStatusCode().value() != 404) throw e;
                // Missing: a later section may create it (see drift).
            }
        }
    }

    /** Waits for the background work to finish, and fails with its error if it failed. */
    private void waitUntilDone(String hostId) {
        waitUntilIdle(hostId);
        ObservabilityStack s = require(hostId);
        if (s.isLastResultFailed()) {
            throw new IacException(
                    "setting up the stack failed: "
                            + (s.getLastResult() == null
                                    ? "see the Observability page"
                                    : s.getLastResult()));
        }
    }

    private void waitUntilIdle(String hostId) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (observability.isBusy(hostId)) {
            if (System.currentTimeMillis() > deadline) {
                throw new IacException(
                        "still setting up after 30 minutes; check the Observability page");
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IacException("interrupted while waiting for the stack");
            }
        }
    }

    @Override
    public boolean deletesOnRemove(ManagedResource record, StackSpec appliedSpec) {
        return appliedSpec != null
                && appliedSpec.getOnRemove() != null
                && appliedSpec.getOnRemove() != DatabasesHandler.OnRemove.KEEP;
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        String hostId = record.getHostId();
        if (hostId == null || stacks.findByDockerHostId(hostId).isEmpty()) return;
        boolean data = false;
        try {
            StackSpec applied = IacJson.SPEC.readValue(record.getAppliedSpec(), StackSpec.class);
            data = applied.getOnRemove() == DatabasesHandler.OnRemove.DELETE_DATA;
        } catch (Exception e) {
            log.debug(
                    "Could not read the applied entry of {}: {}",
                    record.getEntryKey(),
                    e.getMessage());
        }
        waitUntilIdle(hostId);
        observability.remove(hostId, data);
    }

    @Override
    public Map<String, StackSpec> export(ExportContext ctx) {
        Map<String, StackSpec> out = new LinkedHashMap<>();
        for (ObservabilityStack st : stacks.findAll()) {
            if (!hosts.existsById(st.getDockerHostId())) continue;
            StackSpec s = new StackSpec();
            s.setMimir(st.isMimirEnabled());
            s.setLoki(st.isLokiEnabled());
            s.setTempo(st.isTempoEnabled());
            s.setCollectLogs(st.isCollectLogs());
            s.setCollectMetrics(st.isCollectMetrics());
            s.setOtlp(st.isOtlpEnabled());
            s.setMetricsRetentionDays(st.getMetricsRetentionDays());
            s.setLogsRetentionDays(st.getLogsRetentionDays());
            s.setTracesRetentionDays(st.getTracesRetentionDays());
            if ("PUBLIC".equals(st.getAccess())) {
                s.setAccess(Access.PUBLIC);
                s.setHostname(st.getHostname());
                String url = st.getSignInUrl();
                if (url != null) {
                    int cut = url.indexOf("/observability/grafana/");
                    String base = cut > 0 ? url.substring(0, cut) : null;
                    if (base != null && !base.equals(trimSlash(baseUrl))) {
                        s.setDockerManagerUrl(base);
                    }
                }
            } else {
                s.setAccess(Access.LOCAL);
                s.setPort(st.getLocalPort());
            }
            if (st.isOtlpEnabled()) {
                try {
                    for (ObservabilityService.Member m :
                            observability.members(st.getDockerHostId())) {
                        s.getContainers().add(m.name());
                    }
                } catch (RuntimeException e) {
                    log.debug("Could not list OpenTelemetry members: {}", e.getMessage());
                }
            }
            out.put(ctx.hostKey(st.getDockerHostId()), s);
        }
        return out;
    }

    /** Every setting, with defaults for what the entry leaves out, so the file is the truth. */
    private ObservabilityService.Settings settings(StackSpec s) {
        boolean pub = s.getAccess() == Access.PUBLIC;
        boolean loki = s.getLoki() == null || s.getLoki();
        String url = s.getDockerManagerUrl();
        if (url == null || url.isBlank()) url = trimSlash(baseUrl);
        return new ObservabilityService.Settings(
                s.getMimir() == null || s.getMimir(),
                loki,
                s.getTempo() == null || s.getTempo(),
                s.getCollectLogs() == null ? loki : s.getCollectLogs(),
                s.getCollectMetrics() == null || s.getCollectMetrics(),
                s.getOtlp() == null || s.getOtlp(),
                s.getMetricsRetentionDays() == null ? 30 : s.getMetricsRetentionDays(),
                s.getLogsRetentionDays() == null ? 14 : s.getLogsRetentionDays(),
                s.getTracesRetentionDays() == null ? 7 : s.getTracesRetentionDays(),
                pub ? "PUBLIC" : "LOCAL",
                pub ? s.getHostname().trim() : null,
                pub ? null : s.getPort() == null ? 3000 : s.getPort(),
                pub && url != null && !url.isBlank() ? url : null);
    }

    private static String trimSlash(String s) {
        return s == null ? null : s.trim().replaceFirst("/+$", "");
    }

    private ObservabilityStack require(String hostId) {
        return stacks.findByDockerHostId(hostId)
                .orElseThrow(() -> new IacException("the host has no observability stack"));
    }

    private static Target target(ObservabilityStack s, String hostKey) {
        return new Target(s.getId(), s.getDockerHostId(), "Observability on " + hostKey);
    }
}
