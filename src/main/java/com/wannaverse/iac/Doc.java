package com.wannaverse.iac;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a field (or a whole entry type) of the configuration file. Used for the JSON schema
 * that editors read for completion and hover help, and for the generated reference.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface Doc {
    String value();

    /** The entry is rejected when this field is missing. */
    boolean required() default false;
}
