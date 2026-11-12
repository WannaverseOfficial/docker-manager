package com.wannaverse.iac;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** One apply of a configuration file, with the progress of each entry. Read while it runs. */
public class ConfigRun {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        /** Some entries failed; the rest were applied. */
        PARTIAL,
        /** Nothing was applied (the file has errors), or every change failed. */
        FAILED
    }

    public enum ItemStatus {
        PENDING,
        RUNNING,
        DONE,
        FAILED,
        SKIPPED
    }

    public static class Item {
        public final String section;
        public final String key;
        public final Plan.Action action;
        public final List<String> fields;
        public volatile String label;
        public final String hostId;
        public volatile ItemStatus status = ItemStatus.PENDING;
        public volatile String message;

        Item(Plan.Item p) {
            this.section = p.section();
            this.key = p.key();
            this.action = p.action();
            this.fields = p.fields();
            this.label = p.label();
            this.hostId = p.hostId();
        }
    }

    public final String id = UUID.randomUUID().toString();
    public final String source;
    public final String startedBy;
    public final long startedAt = System.currentTimeMillis();
    public volatile long finishedAt;
    public volatile Status status = Status.RUNNING;
    public final List<Item> items = new CopyOnWriteArrayList<>();
    public final List<String> problems = new CopyOnWriteArrayList<>();
    public final List<String> warnings = new CopyOnWriteArrayList<>();

    /** Lines for the person who applied (such as generated passwords). Not stored. */
    public final List<String> notes = new CopyOnWriteArrayList<>();

    public ConfigRun(String source, String startedBy) {
        this.source = source;
        this.startedBy = startedBy;
    }

    void finish(Status s) {
        status = s;
        finishedAt = System.currentTimeMillis();
    }
}
