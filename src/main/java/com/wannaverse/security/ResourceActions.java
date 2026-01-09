package com.wannaverse.security;

import com.wannaverse.persistence.Resource;

import java.util.Map;
import java.util.Set;

public final class ResourceActions {
    private ResourceActions() {}

    public static final Map<Resource, Set<String>> ACTIONS =
            Map.of(
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
                            "health"),
                    Resource.IMAGES,
                    Set.of("list", "read", "pull", "delete", "inspect", "cleanup"),
                    Resource.VOLUMES,
                    Set.of("list", "read", "create", "delete"),
                    Resource.NETWORKS,
                    Set.of("list", "read", "create", "delete", "connect", "disconnect", "inspect"),
                    Resource.DOCKER_HOSTS,
                    Set.of("list", "read", "create", "delete", "ping"),
                    Resource.GIT_REPOS,
                    Set.of(
                            "list",
                            "read",
                            "create",
                            "update",
                            "delete",
                            "deploy",
                            "poll",
                            "regenerate_webhook"),
                    Resource.DEPLOYMENTS,
                    Set.of("list", "read", "cancel", "logs", "stream_logs"),
                    Resource.USERS,
                    Set.of(
                            "list",
                            "read",
                            "create",
                            "update",
                            "delete",
                            "reset_password",
                            "manage_permissions"),
                    Resource.USER_GROUPS,
                    Set.of(
                            "list",
                            "read",
                            "create",
                            "update",
                            "delete",
                            "manage_members",
                            "manage_permissions"));

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
