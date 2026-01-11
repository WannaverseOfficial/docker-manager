package com.wannaverse.security;

import static java.util.Map.entry;

import com.wannaverse.persistence.Resource;

import java.util.Map;
import java.util.Set;

public final class ResourceActions {
    private ResourceActions() {}

    public static final Map<Resource, Set<String>> ACTIONS =
            Map.ofEntries(
                    entry(
                            Resource.DOCKER_HOSTS,
                            Set.of("list", "read", "create", "update", "delete", "ping")),
                    entry(
                            Resource.CONTAINERS,
                            Set.of(
                                    "list",
                                    "read",
                                    "create",
                                    "delete",
                                    "start",
                                    "stop",
                                    "restart",
                                    "kill",
                                    "pause",
                                    "unpause",
                                    "rename",
                                    "exec",
                                    "logs",
                                    "inspect",
                                    "processes",
                                    "health")),
                    entry(
                            Resource.IMAGES,
                            Set.of("list", "read", "pull", "delete", "inspect", "cleanup")),
                    entry(Resource.VOLUMES, Set.of("list", "read", "create", "delete", "inspect")),
                    entry(
                            Resource.NETWORKS,
                            Set.of(
                                    "list",
                                    "read",
                                    "create",
                                    "delete",
                                    "connect",
                                    "disconnect",
                                    "inspect")),
                    entry(
                            Resource.GIT_REPOS,
                            Set.of(
                                    "list",
                                    "read",
                                    "create",
                                    "update",
                                    "delete",
                                    "deploy",
                                    "poll",
                                    "regenerate_webhook")),
                    entry(
                            Resource.DEPLOYMENTS,
                            Set.of("list", "read", "cancel", "logs", "stream_logs")),
                    entry(
                            Resource.USERS,
                            Set.of(
                                    "list",
                                    "read",
                                    "create",
                                    "update",
                                    "delete",
                                    "reset_password",
                                    "manage_permissions")),
                    entry(
                            Resource.USER_GROUPS,
                            Set.of(
                                    "list",
                                    "read",
                                    "create",
                                    "update",
                                    "delete",
                                    "manage_members",
                                    "manage_permissions")),
                    entry(Resource.AUDIT_LOGS, Set.of("list", "read", "export")),
                    entry(
                            Resource.NOTIFICATIONS,
                            Set.of("list", "read", "create", "update", "delete", "test")),
                    entry(Resource.DOCKER_OPERATIONS, Set.of("prune", "system_info")),
                    entry(
                            Resource.STATE_SNAPSHOTS,
                            Set.of("list", "read", "create", "delete", "restore")),
                    entry(
                            Resource.COMPOSE_DEPLOYMENTS,
                            Set.of("list", "read", "create", "update", "delete", "start", "stop")),
                    entry(Resource.ROLLBACK, Set.of("execute", "list", "read")),
                    entry(
                            Resource.IMAGE_POLICIES,
                            Set.of("list", "read", "create", "update", "delete")),
                    entry(
                            Resource.REGISTRIES,
                            Set.of("list", "read", "create", "update", "delete", "test")),
                    entry(
                            Resource.INGRESS,
                            Set.of("read", "enable", "disable", "reload", "configure")),
                    entry(
                            Resource.INGRESS_ROUTES,
                            Set.of("list", "read", "create", "update", "delete", "preview")),
                    entry(
                            Resource.INGRESS_CERTIFICATES,
                            Set.of(
                                    "list", "read", "create", "delete", "upload", "request",
                                    "renew")),
                    entry(
                            Resource.TEMPLATES,
                            Set.of("list", "read", "create", "update", "delete", "deploy")),
                    entry(
                            Resource.PIPELINES,
                            Set.of(
                                    "list",
                                    "read",
                                    "create",
                                    "update",
                                    "delete",
                                    "trigger",
                                    "duplicate")),
                    entry(
                            Resource.PIPELINE_EXECUTIONS,
                            Set.of("list", "read", "cancel", "logs", "stream_logs", "artifacts")),
                    entry(
                            Resource.DEPLOYMENT_ENVIRONMENTS,
                            Set.of(
                                    "list",
                                    "read",
                                    "create",
                                    "update",
                                    "delete",
                                    "deploy",
                                    "switch",
                                    "rollback")));

    public static boolean isValidAction(Resource resource, String action) {
        if ("*".equals(action)) {
            return true;
        }
        Set<String> validActions = ACTIONS.get(resource);
        return validActions != null && validActions.contains(action);
    }

    public static Set<String> getActionsForResource(Resource resource) {
        return ACTIONS.getOrDefault(resource, Set.of());
    }
}
