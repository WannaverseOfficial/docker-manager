package com.wannaverse.iac;

import com.wannaverse.persistence.ManagedResource;

import java.util.List;
import java.util.Optional;

/** What a handler can ask while a file is planned or applied. */
public interface ApplyContext {

    /** True while planning: handlers must not change anything. */
    boolean planning();

    /**
     * The id of the Docker host an entry refers to: a key of the {@code hosts} section, or the name
     * or id of a host added by hand. Null or blank means the only host (an error when there are
     * several). While planning, returns null for a host this apply is about to add.
     *
     * @throws IacException when no host matches
     */
    String hostId(String ref);

    /**
     * The id of what another entry refers to: an entry of {@code section} with this key, or
     * something made by hand that the section's handler finds by this name. While planning, returns
     * null for an entry this apply is about to create.
     *
     * @throws IacException when nothing matches
     */
    String ref(String section, String key);

    /** Whether the file being applied has an entry with this key in the section. */
    boolean declared(String section, String key);

    Optional<ManagedResource> record(String section, String key);

    List<ManagedResource> records(String section);

    /**
     * A line for the person applying the file, shown with the result (for example a generated
     * password). Not logged.
     */
    void note(String message);

    /**
     * True in the check after an apply's changes, when every section has been applied. Links to
     * things that still don't exist are errors then; earlier they may just not be created yet.
     */
    boolean settling();

    /** Who is applying: an administrator's user name, or "config file". */
    String actor();
}
