package com.wannaverse.dns;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * Tells the DNS service that something its automatic records come from has changed (an ingress
 * route, the ingress itself, a host's public address), however it was changed: the web API, the
 * configuration file or a rollout.
 */
public final class DnsInputs {
    private static volatile Runnable listener;

    private DnsInputs() {}

    static void register(Runnable r) {
        listener = r;
    }

    public static void changed() {
        Runnable l = listener;
        if (l != null) l.run();
    }

    /** JPA entity listener for the entities automatic records are made from. */
    public static class EntityListener {
        @PostPersist
        @PostUpdate
        @PostRemove
        void onChange(Object entity) {
            changed();
        }
    }
}
