package com.wannaverse.iac;

import com.wannaverse.persistence.ManagedResourceRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/** Enforces {@link ConfigLocked}: refuses changes to what the configuration file manages. */
@Component
public class ConfigLockInterceptor implements HandlerInterceptor {

    public static final String MESSAGE =
            "This is managed by the configuration file. Change it in the file instead"
                    + " (see Configuration).";

    private final ConfigEngine engine;
    private final ManagedResourceRepository managed;

    public ConfigLockInterceptor(@Lazy ConfigEngine engine, ManagedResourceRepository managed) {
        this.engine = engine;
        this.managed = managed;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod method)) return true;
        ConfigLocked lock = method.getMethodAnnotation(ConfigLocked.class);
        if (lock == null) return true;

        @SuppressWarnings("unchecked")
        Map<String, String> vars =
                (Map<String, String>)
                        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String id = lock.id().isEmpty() || vars == null ? null : vars.get(lock.id());
        String host = lock.host().isEmpty() || vars == null ? null : vars.get(lock.host());

        boolean locked;
        if (lock.id().isEmpty()) {
            locked = !managed.findBySection(lock.section()).isEmpty();
        } else {
            ResourceHandler<?> h = engine.handler(lock.section());
            locked =
                    h != null
                            ? h.isManaged(id, host, managed)
                            : id != null && managed.existsBySectionAndTargetId(lock.section(), id);
        }
        if (!locked) return true;
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":\"" + MESSAGE + "\",\"code\":\"MANAGED_BY_CONFIG\"}");
        return false;
    }
}
