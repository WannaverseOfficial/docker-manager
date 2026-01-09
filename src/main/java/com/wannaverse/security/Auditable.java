package com.wannaverse.security;

import com.wannaverse.persistence.Resource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method for audit logging. When applied, the AuditAspect will automatically log
 * the action with details.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    /** The resource type being accessed/modified. */
    Resource resource();

    /** The action being performed (e.g., "create", "delete", "start"). */
    String action();

    /**
     * The name of the path variable or request parameter that contains the resource ID. Leave empty
     * if there's no specific resource ID.
     */
    String resourceIdParam() default "";

    /**
     * Whether to capture the request body in the audit log details. Sensitive fields will be
     * automatically redacted.
     */
    boolean captureRequestBody() default false;
}
