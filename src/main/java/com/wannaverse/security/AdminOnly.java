package com.wannaverse.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts an endpoint (or every endpoint of a controller) to administrators, regardless of any
 * granted permissions. Used for operations that could otherwise be used to escalate privileges,
 * such as managing users, groups and permissions.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOnly {}
