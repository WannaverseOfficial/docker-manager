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

    public PermissionInterceptor(
            PermissionService permissionService, DockerHostService dockerHostService) {
        this.permissionService = permissionService;
        this.dockerHostService = dockerHostService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        SecurityContext ctx = SecurityContextHolder.getContext();

        RequirePermission annotation = handlerMethod.getMethodAnnotation(RequirePermission.class);

        if (annotation == null) {
            if (ctx == null || !ctx.isAuthenticated()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Authentication required\"}");
                return false;
            }
            return true;
        }

        if (ctx == null || !ctx.isAuthenticated()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required\"}");
            return false;
        }

        if (ctx.isAdmin()) {
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
        identifiers.add(resourceId);

        if (resource == Resource.CONTAINERS && hostId != null) {
            try {
                var api = dockerHostService.getDockerAPI(hostId);
                var containers = api.listAllContainers();
                for (var container : containers) {
                    if (container.getId().equals(resourceId)
                            || container.getId().startsWith(resourceId)
                            || resourceId.startsWith(container.getId().substring(0, 12))) {
                        if (container.getNames() != null) {
                            for (String name : container.getNames()) {
                                String cleanName = name.startsWith("/") ? name.substring(1) : name;
                                if (!identifiers.contains(cleanName)) {
                                    identifiers.add(cleanName);
                                }
                            }
                        }
                        identifiers.add(container.getId());
                        identifiers.add(container.getId().substring(0, 12));
                        break;
                    }
                    if (container.getNames() != null) {
                        for (String name : container.getNames()) {
                            String cleanName = name.startsWith("/") ? name.substring(1) : name;
                            if (cleanName.equals(resourceId)) {
                                identifiers.add(container.getId());
                                identifiers.add(container.getId().substring(0, 12));
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not resolve container identifiers: {}", e.getMessage());
            }
        }

        return identifiers;
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
