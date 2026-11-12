package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.Stack;
import com.wannaverse.persistence.StackRepository;
import com.wannaverse.service.StackService;

import lombok.Data;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** {@code stacks}: compose projects, as on the Stacks page. */
@Component
public class StacksHandler implements ResourceHandler<StacksHandler.StackSpec> {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");

    /** The longest a deploy may take before the apply reports it as failed. */
    private static final long DEPLOY_TIMEOUT_MS = 30 * 60 * 1000L;

    @Data
    @Doc("A compose stack")
    public static class StackSpec {
        @Doc("The Docker host (a key of hosts:, or a host's name). Default: the only host.")
        private String host;

        @Doc(
                "The compose project name: lowercase letters, digits, '-' and '_'. Defaults to the"
                        + " entry's key.")
        private String name;

        @Doc(
                value =
                        "The compose file (use a | block). Its own ${VARIABLES} are left for"
                                + " compose to fill in from env.",
                required = true)
        private String compose;

        @Doc(
                "Variables for the compose file, written to the stack's .env. Use ${env:NAME} or"
                        + " ${file:...} for secrets.")
        private Map<String, Secret> env;

        @Doc(
                "Deploy (docker compose up) whenever the entry changes (default true). false only"
                        + " saves the stack.")
        private Boolean deploy;

        @Doc(
                "When the entry is removed, also delete the stack's named volumes and their data"
                        + " (default false: volumes are kept)")
        private Boolean removeVolumes;
    }

    private final StackService stacks;
    private final StackRepository repo;
    private final DockerHostRepository hosts;

    public StacksHandler(StackService stacks, StackRepository repo, DockerHostRepository hosts) {
        this.stacks = stacks;
        this.repo = repo;
        this.hosts = hosts;
    }

    @Override
    public String section() {
        return "stacks";
    }

    @Override
    public int order() {
        return 220;
    }

    @Override
    public Class<StackSpec> specType() {
        return StackSpec.class;
    }

    @Override
    public String description() {
        return "Compose stacks: a compose file and its variables, deployed on a host";
    }

    @Override
    public void validate(Entry<StackSpec> entry, ApplyContext ctx) {
        String name = entry.nameOr(entry.spec().getName());
        // Compose names volumes after the project, so a renamed stack would start empty.
        DockerEntries.refuseMove(
                ctx, section(), entry.key(), entry.spec().getHost(), name, false, "stack");
        if (!NAME.matcher(name).matches()) {
            throw new IacException(
                    "name: stack names use lowercase letters, digits, '-' and '_', and start with"
                            + " a letter or digit (set name: when the key doesn't fit)");
        }
        if (entry.spec().getEnv() != null) {
            for (String k : entry.spec().getEnv().keySet()) {
                if (!k.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                    throw new IacException("env: \"" + k + "\" is not a variable name");
                }
            }
        }
        ctx.hostId(entry.spec().getHost());
        try {
            stacks.validate(entry.spec().getCompose(), true);
        } catch (ResponseStatusException e) {
            throw new IacException("compose: " + reason(e));
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<StackSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        Optional<Stack> stack = repo.findByDockerHostIdAndName(hostId, name);
        if (stack.isEmpty()) return Optional.empty();
        if (stack.get().getSource() == Stack.Source.GIT) {
            throw new IacException(
                    "the stack "
                            + name
                            + " is deployed from a git repository; manage that repository under"
                            + " gitRepositories: instead");
        }
        return Optional.of(new Target(name, hostId, name));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return repo.findByDockerHostIdAndName(record.getHostId(), record.getTargetId()).isPresent();
    }

    @Override
    public Target create(Entry<StackSpec> entry, ApplyContext ctx) {
        StackSpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());
        try {
            stacks.create(
                    hostId,
                    new StackService.SaveRequest(name, s.getCompose(), envText(s), null, false),
                    ctx.actor(),
                    true);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 409 && reason(e).contains("already running")) {
                throw new IacException(
                        "a compose project called "
                                + name
                                + " is already running on this host but is not a Docker Manager"
                                + " stack. Import it on the Stacks page first (the file then takes"
                                + " it over), or remove it.");
            }
            throw e;
        }
        if (deploys(s)) deploy(hostId, name, ctx);
        return new Target(name, hostId, name);
    }

    @Override
    public Target update(
            Entry<StackSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        StackSpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());
        if (!hostId.equals(record.getHostId()) || !name.equals(record.getTargetId())) {
            // Another host or name is another compose project: create it, remove the old one.
            Target t =
                    repo.findByDockerHostIdAndName(hostId, name).isPresent()
                            ? updateExisting(hostId, name, s, Changes.all(), ctx)
                            : create(entry, ctx);
            delete(record, ctx);
            return t;
        }
        return updateExisting(hostId, name, s, changes, ctx);
    }

    private Target updateExisting(
            String hostId, String name, StackSpec s, Changes changes, ApplyContext ctx) {
        Stack stack =
                repo.findByDockerHostIdAndName(hostId, name)
                        .orElseThrow(() -> new IacException("the stack no longer exists"));
        boolean contentChanged =
                !s.getCompose().equals(stack.getComposeContent())
                        || !java.util.Objects.equals(
                                blankToNull(envText(s)), blankToNull(stack.getEnvContent()));
        if (contentChanged) {
            stacks.update(
                    hostId,
                    name,
                    new StackService.SaveRequest(name, s.getCompose(), envText(s), null, false),
                    true);
        }
        // Deploy when the files changed, when asked to start deploying, and when taking over.
        // Also when the files didn't change here but differ from the last successful apply: the
        // last deploy failed (the files are saved before deploying), so try it again.
        if (deploys(s)
                && (contentChanged
                        || changes.has("compose", "env", "deploy")
                        || changes.takeover())) {
            deploy(hostId, name, ctx);
        }
        return new Target(name, hostId, name);
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (!hosts.existsById(record.getHostId())) return;
        if (repo.findByDockerHostIdAndName(record.getHostId(), record.getTargetId()).isEmpty()) {
            return;
        }
        boolean volumes = false;
        try {
            StackSpec applied =
                    com.wannaverse.iac.IacJson.SPEC.readValue(
                            record.getAppliedSpec(), StackSpec.class);
            volumes = Boolean.TRUE.equals(applied.getRemoveVolumes());
        } catch (Exception ignored) {
            // Written by an older version: keep the volumes.
        }
        run(
                record.getHostId(),
                record.getTargetId(),
                volumes ? StackService.Action.REMOVE_WITH_VOLUMES : StackService.Action.REMOVE,
                ctx);
    }

    @Override
    public Map<String, StackSpec> export(ExportContext ctx) {
        Map<String, StackSpec> out = new LinkedHashMap<>();
        for (Stack stack : repo.findAll()) {
            if (stack.getSource() == Stack.Source.GIT) continue;
            StackSpec s = new StackSpec();
            s.setHost(ctx.hostKey(stack.getDockerHostId()));
            s.setCompose(DockerEntries.escape(stack.getComposeContent()));
            if (stack.getLastDeployedAt() == null) s.setDeploy(false);
            String key = ctx.key(section(), stack.getName(), stack.getName());
            if (out.containsKey(key)) key = key + "-" + ctx.hostKey(stack.getDockerHostId());
            Map<String, Secret> env =
                    DockerEntries.hideSecrets(
                            DockerEntries.parseEnvFile(stack.getEnvContent()),
                            ctx,
                            section(),
                            key,
                            "stack " + stack.getName());
            if (!env.isEmpty()) s.setEnv(env);
            if (!key.equals(stack.getName())) s.setName(stack.getName());
            out.put(key, s);
        }
        return out;
    }

    private void deploy(String hostId, String name, ApplyContext ctx) {
        run(hostId, name, StackService.Action.DEPLOY, ctx);
    }

    /** Runs a stack action and waits for it, failing with compose's last lines. */
    @SuppressWarnings("unchecked")
    private void run(String hostId, String name, StackService.Action action, ApplyContext ctx) {
        StackService.Job job = stacks.start(hostId, name, action, ctx.actor());
        long deadline = System.currentTimeMillis() + DEPLOY_TIMEOUT_MS;
        while ("running".equals(job.status)) {
            if (System.currentTimeMillis() > deadline) {
                throw new IacException(
                        "still running after 30 minutes; follow it on the stack's page");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IacException("interrupted");
            }
        }
        if (!"success".equals(job.status)) {
            List<String> lines = (List<String>) job.view(0).get("lines");
            List<String> tail = lines.subList(Math.max(0, lines.size() - 4), lines.size());
            throw new IacException(
                    "docker compose "
                            + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + " failed: "
                            + String.join(" / ", tail));
        }
    }

    private static boolean deploys(StackSpec s) {
        return s.getDeploy() == null || s.getDeploy();
    }

    private static String envText(StackSpec s) {
        if (s.getEnv() == null || s.getEnv().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        s.getEnv()
                .forEach(
                        (k, v) ->
                                sb.append(k)
                                        .append('=')
                                        .append(v == null || v.value() == null ? "" : v.value())
                                        .append('\n'));
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String reason(ResponseStatusException e) {
        return e.getReason() == null ? e.getMessage() : e.getReason();
    }
}
