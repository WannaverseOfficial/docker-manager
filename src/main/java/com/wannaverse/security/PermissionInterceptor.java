package com.wannaverse.security;

import com.wannaverse.service.PermissionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

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
