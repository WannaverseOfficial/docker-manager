package com.wannaverse.iac;

/** Helps handlers write today's setup as file entries. */
public interface ExportContext {

    /** The key the host has in the exported {@code hosts} section. */
    String hostKey(String hostId);

    /**
     * The key for a resource in the export: its key in the configuration file when the file manages
     * it, otherwise the fallback (usually its name).
     */
    String key(String section, String targetId, String fallback);

    /**
     * A placeholder for a secret that can't be exported, such as {@code
     * ${env:DM_USERS_ALICE_PASSWORD}}. The export lists every variable it needs.
     *
     * @param what describes it for the list, such as "password of user alice"
     */
    Secret secret(String section, String key, String field, String what);
}
