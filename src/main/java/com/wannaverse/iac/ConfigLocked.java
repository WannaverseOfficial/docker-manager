package com.wannaverse.iac;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Refuses an API request that would change something the configuration file manages, so the file
 * stays the one place it is set. Put it on endpoints that change settings or delete, not on
 * everyday actions (start, stop, deploy, back up).
 *
 * <p>The section's handler decides whether the request's resource is managed: by default, whether a
 * record of that section has the id found in the {@link #id} path variable.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigLocked {
    /** The configuration file section, such as "users" or "ingress.routes". */
    String section();

    /**
     * The path variable with the resource's id. Empty for singletons such as SMTP: the request is
     * refused whenever the section has a managed entry.
     */
    String id() default "";

    /** The path variable with the Docker host's id, if the path has one. */
    String host() default "";
}
