package com.wannaverse.security;

import com.wannaverse.persistence.Resource;
import com.wannaverse.service.DockerHostService;
import com.wannaverse.service.PermissionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);

    private final PermissionService permissionService;
    private final DockerHostService dockerHostService;
    private final UserStatusCache userStatusCache;

    public PermissionInterceptor(
            PermissionService permissionService,
            DockerHostService dockerHostService,
            UserStatusCache userStatusCache) {
        this.permissionService = permissionService;
        this.dockerHostService = dockerHostService;
        this.userStatusCache = userStatusCache;
    }

    private static boolean deny(HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        return false;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // Streamed responses (downloads, live logs) finish on a second, ASYNC dispatch of the
        // same request. It was checked on the first one; the JWT filter doesn't run again, so
        // checking now would reject it halfway through the response.
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
            return true;
        }

        SecurityContext ctx = SecurityContextHolder.getContext();

        if (ctx == null || !ctx.isAuthenticated()) {
            return deny(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
        }

        // The token says who the user is; their current rights come from the database, so that
        // demoting, disabling or deleting a user takes effect immediately.
        UserStatusCache.Status status = userStatusCache.get(ctx.getUserId());
        if (!status.exists() || !status.enabled()) {
            return deny(response, HttpServletResponse.SC_UNAUTHORIZED, "Account is disabled");
        }
        if (status.admin() != ctx.isAdmin()
                || status.mustChangePassword() != ctx.isMustChangePassword()) {
            ctx =
                    SecurityContext.builder()
                            .userId(ctx.getUserId())
                            .username(ctx.getUsername())
                            .admin(status.admin())
                            .mustChangePassword(status.mustChangePassword())
                            .build();
            SecurityContextHolder.setContext(ctx);
        }

        // A user who must change their password may only use the auth endpoints until they do.
        // (This was previously enforced only by the UI.)
        if (ctx.isMustChangePassword() && !request.getRequestURI().startsWith("/api/auth/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"error\":\"Password change"
                                    + " required\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
            return false;
        }

        boolean adminOnly =
                handlerMethod.hasMethodAnnotation(AdminOnly.class)
                        || handlerMethod.getBeanType().isAnnotationPresent(AdminOnly.class);
        if (adminOnly && !ctx.isAdmin()) {
            return deny(
                    response, HttpServletResponse.SC_FORBIDDEN, "Administrator access required");
        }

        RequirePermission annotation = handlerMethod.getMethodAnnotation(RequirePermission.class);

        if (annotation == null || ctx.isAdmin()) {
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> pathVars =
                (Map<String, String>)
                        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

        String hostId = null;
        if (!annotation.hostIdParam().isEmpty() && pathVars != null) {
            hostId = pathVars.get(annotation.hostIdParam());
        }

        String resourceId = null;
        if (!annotation.resourceIdParam().isEmpty() && pathVars != null) {
            resourceId = pathVars.get(annotation.resourceIdParam());
        }

        boolean hasPermission;
        if ("list".equals(annotation.action())) {
            hasPermission =
                    permissionService.hasAnyPermission(
                            ctx.getUserId(), annotation.resource(), annotation.action(), hostId);
        } else {
            List<String> resourceIdentifiers =
                    getResourceIdentifiers(annotation.resource(), hostId, resourceId);
            hasPermission =
                    checkPermissionWithIdentifiers(
                            ctx.getUserId(),
                            annotation.resource(),
                            annotation.action(),
                            hostId,
                            resourceIdentifiers);
        }

        if (!hasPermission) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"error\":\"Insufficient permissions for "
                                    + annotation.resource()
                                    + "."
                                    + annotation.action()
                                    + "\"}");
            return false;
        }

        return true;
    }

    private List<String> getResourceIdentifiers(
            Resource resource, String hostId, String resourceId) {
        List<String> identifiers = new ArrayList<>();
        if (resourceId == null) {
            return identifiers;
        }
        if (resource == Resource.CONTAINERS && hostId != null) {
            // Resolve the identifier to the one container Docker will act on, and check only
            // that container's ID and names. Matching loosely could let a permission on one
            // container authorise a request that Docker resolves to another.
            try {
                var container =
                        resolveContainer(
                                dockerHostService.getDockerAPI(hostId).listAllContainers(),
                                resourceId);
                if (container != null) {
                    identifiers.add(container.getId());
                    identifiers.add(container.getId().substring(0, 12));
                    if (container.getNames() != null) {
                        for (String name : container.getNames()) {
                            String clean = name.startsWith("/") ? name.substring(1) : name;
                            if (!identifiers.contains(clean)) identifiers.add(clean);
                        }
                    }
                    return identifiers;
                }
            } catch (Exception e) {
                log.debug("Could not resolve container identifiers: {}", e.getMessage());
            }
        }
        identifiers.add(resourceId);
        return identifiers;
    }

    /**
     * Docker's own lookup order: an exact ID, then an exact name, then an ID prefix that matches
     * exactly one container. Returns null when nothing (or more than one prefix match) is found.
     */
    static com.github.dockerjava.api.model.Container resolveContainer(
            List<com.github.dockerjava.api.model.Container> containers, String ref) {
        if (ref == null || ref.isBlank()) return null;
        String name = ref.startsWith("/") ? ref.substring(1) : ref;
        for (var c : containers) {
            if (c.getId().equals(ref)) return c;
        }
        for (var c : containers) {
            if (c.getNames() == null) continue;
            for (String n : c.getNames()) {
                if ((n.startsWith("/") ? n.substring(1) : n).equals(name)) return c;
            }
        }
        com.github.dockerjava.api.model.Container match = null;
        for (var c : containers) {
            if (c.getId().startsWith(ref)) {
                if (match != null) return null;
                match = c;
            }
        }
        return match;
    }

    private boolean checkPermissionWithIdentifiers(
            String userId,
            Resource resource,
            String action,
            String hostId,
            List<String> identifiers) {
        if (identifiers.isEmpty()) {
            return permissionService.hasPermission(userId, resource, action, hostId, null);
        }

        for (String identifier : identifiers) {
            if (permissionService.hasPermission(userId, resource, action, hostId, identifier)) {
                return true;
            }
        }
        return false;
    }
}
