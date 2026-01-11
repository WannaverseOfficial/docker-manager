package com.wannaverse.security;

import com.wannaverse.persistence.Resource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    Resource resource();

    String action();

    String resourceIdParam() default "";

    boolean captureRequestBody() default false;
}
