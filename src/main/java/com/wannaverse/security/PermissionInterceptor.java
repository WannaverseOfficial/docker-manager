package com.wannaverse.security;

import com.wannaverse.service.PermissionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Interceptor that enforces authentication and permission checks on all API endpoints.
 *
 * <p>By default, all endpoints require authentication unless explicitly excluded via the
 * SecurityConfig exclusion patterns. Endpoints with @RequirePermission additionally enforce
 * permission checks.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;

    public PermissionInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        SecurityContext ctx = SecurityContextHolder.getContext();

        // Check for @RequirePermission annotation
        RequirePermission annotation = handlerMethod.getMethodAnnotation(RequirePermission.class);

        // If no @RequirePermission, still require authentication for all API endpoints
        // This is a fail-safe - endpoints without @RequirePermission still need auth
        if (annotation == null) {
            if (ctx == null || !ctx.isAuthenticated()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Authentication required\"}");
                return false;
            }
            return true;
        }

        // For endpoints with @RequirePermission, check authentication and permissions
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

        boolean hasPermission =
                permissionService.hasPermission(
                        ctx.getUserId(),
                        annotation.resource(),
                        annotation.action(),
                        hostId,
                        resourceId);

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
}
