package com.wannaverse.iac;

import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;

import java.util.Map;
import java.util.Optional;

/**
 * One section of the configuration file (such as {@code users} or {@code stacks}): how its entries
 * are created, changed and removed, and how today's setup is exported as entries.
 *
 * <p>Handlers are Spring beans and are called outside any web request, so methods that load lazy
 * JPA associations need their own {@code @Transactional}. They run with a security context whose
 * user is the administrator who applied the file, or "config file" (no user id) for the file read
 * from {@code DM_CONFIG_FILE}.
 *
 * @param <S> the entry type, a plain Jackson bean. Field names are the keys in the file. Use {@link
 *     Secret} for passwords and keys and {@link Doc} to describe fields.
 */
public interface ResourceHandler<S> {

    /**
     * The key in the file, such as "users". Nested sections are named "parent.field", such as
     * "ingress.routes".
     */
    String section();

    /** Lower runs first when creating and changing, and last when deleting. */
    int order();

    Class<S> specType();

    /** One sentence for the reference and the editor's hover help. */
    String description();

    /**
     * For a section written inside the entries of another one (routes inside an ingress entry): the
     * parent's section. The parent entry's key becomes the first part of this entry's key, as in
     * "web-01/app".
     */
    default String parentSection() {
        return null;
    }

    /** For a nested section: the field of the parent entry that holds these entries. */
    default String parentField() {
        return null;
    }

    /** A single object (such as {@code smtp}) instead of a map of named entries. */
    default boolean singleton() {
        return false;
    }

    /**
     * Checks an entry beyond its field types: required combinations and references to other
     * entries. Throw {@link IacException} with a message that says how to fix the file. Runs while
     * planning, so nothing may be changed here.
     */
    default void validate(Entry<S> entry, ApplyContext ctx) {}

    /**
     * Something made by hand that this entry describes (same name on the same host). Applying the
     * file then takes it over instead of creating a duplicate. Runs while planning.
     */
    Optional<Target> findExisting(Entry<S> entry, ApplyContext ctx);

    /**
     * Whether the resource behind a record still exists. When it was removed by other means,
     * applying creates it again. Runs while planning.
     */
    boolean exists(ManagedResource record, ApplyContext ctx);

    /**
     * Settings whose live state no longer matches the entry although the entry did not change, such
     * as a container that left a database's network when it was re-created. They are passed to
     * {@link #update} as changed. Checked while planning and again after each apply (see {@link
     * ApplyContext#settling()}), so links to things a later section creates are made in the same
     * apply. Empty by default.
     */
    default java.util.Set<String> drift(Entry<S> entry, ManagedResource record, ApplyContext ctx) {
        return java.util.Set.of();
    }

    Target create(Entry<S> entry, ApplyContext ctx) throws Exception;

    /**
     * Brings an existing resource in line with the entry.
     *
     * @param changes the fields that differ from what was last applied; all of them when taking
     *     over something made by hand
     */
    Target update(Entry<S> entry, Changes changes, ManagedResource record, ApplyContext ctx)
            throws Exception;

    void delete(ManagedResource record, ApplyContext ctx) throws Exception;

    /**
     * Whether removing the entry from the file deletes the resource. Kinds that hold data
     * (databases, volumes) override this to keep it unless the entry asked otherwise; Docker
     * Manager then only stops managing it.
     *
     * @param appliedSpec the entry as last applied (secrets hashed)
     */
    default boolean deletesOnRemove(ManagedResource record, S appliedSpec) {
        return true;
    }

    /**
     * The id of something made by hand, found by the name other entries use for it (for example a
     * host's name). Lets entries refer to resources the file does not manage.
     */
    default Optional<String> lookup(String name, ApplyContext ctx) {
        return Optional.empty();
    }

    /**
     * Today's setup as entries, keyed as they should appear in the file. Leave out what Docker
     * Manager creates by itself (system templates, the ingress proxy, a rollout's routes...). Use
     * {@link ExportContext#secret} for secrets.
     */
    Map<String, S> export(ExportContext ctx);

    /**
     * Whether the resource an API request is about is managed by the file, so that the request is
     * refused. By default: a record of this section has this target id (on this host, when the
     * request names one).
     */
    default boolean isManaged(String targetId, String hostId, ManagedResourceRepository managed) {
        if (targetId == null) return false;
        return hostId == null
                ? managed.existsBySectionAndTargetId(section(), targetId)
                : managed.existsBySectionAndTargetIdAndHostId(section(), targetId, hostId);
    }

    /** One entry of the section. */
    record Entry<S>(String key, String parentKey, String localKey, S spec) {
        /** The entry's own name: the given name, or its key in the file. */
        public String nameOr(String explicit) {
            return explicit == null || explicit.isBlank() ? localKey : explicit.trim();
        }
    }

    /** What an entry became: its id, the host it is on and a label for lists. */
    record Target(String id, String hostId, String label) {}
}
