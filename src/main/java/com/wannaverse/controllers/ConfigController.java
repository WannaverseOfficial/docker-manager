package com.wannaverse.controllers;

import com.wannaverse.iac.ConfigExporter;
import com.wannaverse.iac.ConfigRun;
import com.wannaverse.iac.ConfigService;
import com.wannaverse.iac.IacJson;
import com.wannaverse.iac.Plan;
import com.wannaverse.iac.SchemaGenerator;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.SecurityContextHolder;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration as code: plan, apply and export the YAML file that describes Docker Manager's
 * setup.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService config;
    private final ConfigExporter exporter;
    private final ManagedResourceRepository managed;

    public ConfigController(
            ConfigService config, ConfigExporter exporter, ManagedResourceRepository managed) {
        this.config = config;
        this.exporter = exporter;
        this.managed = managed;
    }

    public record DocumentRequest(String yaml) {}

    @GetMapping
    @AdminOnly
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileMode", config.fileMode());
        m.put("file", config.fileMode() ? config.file().toString() : null);
        var state = config.state().orElse(null);
        m.put("source", state == null ? null : state.getSource());
        m.put(
                "appliedAt",
                state == null || state.getAppliedAt() == 0 ? null : state.getAppliedAt());
        m.put("appliedBy", state == null ? null : state.getAppliedBy());
        m.put("lastStatus", state == null ? null : state.getLastStatus());
        String document =
                config.fileMode()
                        ? config.readFile().orElse(null)
                        : config.storedDocument().orElse(null);
        m.put("document", document);
        ConfigRun current = config.currentRun();
        m.put("currentRun", current == null ? null : ConfigService.summary(current, false));
        m.put("lastRun", state == null ? null : parse(state.getLastRunJson()));
        m.put("managedCount", managed.count());
        return m;
    }

    @PostMapping("/plan")
    @AdminOnly
    public Plan plan(@RequestBody DocumentRequest request) {
        return config.plan(text(request), SecurityContextHolder.getCurrentUsername());
    }

    @PostMapping("/apply")
    @AdminOnly
    public ResponseEntity<Map<String, Object>> apply(@RequestBody DocumentRequest request) {
        ConfigRun run = config.applyFromUi(text(request), SecurityContextHolder.getContext());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ConfigService.summary(run, true));
    }

    /** Applies the current file (or the document last applied) again, now. */
    @PostMapping("/reapply")
    @AdminOnly
    public ResponseEntity<Map<String, Object>> reapply() {
        ConfigRun run = config.reconcile(true, SecurityContextHolder.getContext());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ConfigService.summary(run, true));
    }

    @GetMapping("/runs")
    @AdminOnly
    public List<Map<String, Object>> runs() {
        return config.recentRuns().stream().map(r -> ConfigService.summary(r, false)).toList();
    }

    @GetMapping("/runs/{id}")
    @AdminOnly
    public Map<String, Object> run(@PathVariable String id) {
        ConfigRun run =
                config.run(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "No such run"));
        // Notes (such as generated passwords) go only to whoever started the run.
        boolean mine = run.startedBy.equals(SecurityContextHolder.getCurrentUsername());
        return ConfigService.summary(run, mine);
    }

    @GetMapping(value = "/export", produces = "application/yaml")
    @AdminOnly
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"docker-manager.yaml\"")
                .contentType(MediaType.parseMediaType("application/yaml"))
                .body(exporter.export());
    }

    /** The file's JSON schema, for editors. Public: it describes the format, not the setup. */
    @GetMapping("/schema")
    public Map<String, Object> schema() {
        return SchemaGenerator.generate(config.handlers());
    }

    /**
     * What the file manages, so the UI can mark it and hide edit buttons. Everyone signed in gets
     * the ids (which their pages already show); entry keys and labels only go to administrators.
     */
    @GetMapping("/managed")
    public List<Map<String, Object>> managed() {
        var ctx = SecurityContextHolder.getContext();
        boolean admin = ctx != null && ctx.isAdmin();
        return managed.findAll().stream()
                .map(
                        r -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("section", r.getSection());
                            m.put("targetId", r.getTargetId());
                            m.put("hostId", r.getHostId());
                            if (admin) {
                                m.put("key", r.getEntryKey());
                                m.put("label", r.getLabel());
                            }
                            return m;
                        })
                .toList();
    }

    private static Object parse(String json) {
        if (json == null) return null;
        try {
            return IacJson.JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(DocumentRequest request) {
        if (request == null || request.yaml() == null || request.yaml().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The document is empty");
        }
        if (request.yaml().length() > 2_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The document is too large");
        }
        return request.yaml();
    }
}
