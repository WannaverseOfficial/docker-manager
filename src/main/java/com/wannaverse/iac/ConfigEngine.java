package com.wannaverse.iac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Works out what a file changes (the plan) and carries it out (the apply). */
@Component
public class ConfigEngine {

    private static final Logger log = LoggerFactory.getLogger(ConfigEngine.class);

    private final List<ResourceHandler<?>> handlers;
    private final Map<String, ResourceHandler<?>> bySection = new LinkedHashMap<>();
    private final ManagedResourceRepository managed;
    private final DockerHostRepository hosts;

    public ConfigEngine(
            List<ResourceHandler<?>> handlers,
            ManagedResourceRepository managed,
            DockerHostRepository hosts) {
        this.handlers =
                handlers.stream()
                        .sorted(
                                Comparator.<ResourceHandler<?>>comparingInt(ResourceHandler::order)
                                        .thenComparing(ResourceHandler::section))
                        .toList();
        for (ResourceHandler<?> h : this.handlers) {
            if (bySection.put(h.section(), h) != null) {
                throw new IllegalStateException("Two handlers for section " + h.section());
            }
        }
        this.managed = managed;
        this.hosts = hosts;
    }

    public List<ResourceHandler<?>> handlers() {
        return handlers;
    }

    public ResourceHandler<?> handler(String section) {
        return bySection.get(section);
    }

    /** One planned change, with what is needed to carry it out. */
    record Step(
            ResourceHandler<?> handler,
            ResourceHandler.Entry<?> entry,
            ManagedResource record,
            ResourceHandler.Target existing,
            Plan.Action action,
            Changes changes,
            String json,
            Plan.Item item) {}

    record Prepared(ConfigDocument doc, List<Step> steps, Plan plan) {}

    /** Works out the plan. Changes nothing. */
    public Prepared prepare(ConfigDocument doc, String actor) {
        List<String> problems = new ArrayList<>(doc.problems());
        List<String> warnings = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        Ctx ctx = new Ctx(doc, true, false, actor, new ArrayList<>());

        if (problems.isEmpty()) {
            // Records whose key is gone from the file: removed, or renamed (see planEntry).
            Set<String> renamed = new HashSet<>();
            for (ResourceHandler<?> h : handlers) {
                Map<String, ManagedResource> records = new LinkedHashMap<>();
                List<ManagedResource> orphans = new ArrayList<>();
                for (ManagedResource r : managed.findBySection(h.section())) {
                    records.put(r.getEntryKey(), r);
                    if (!doc.declared(h.section(), r.getEntryKey())) orphans.add(r);
                }
                for (ResourceHandler.Entry<?> entry : doc.entries(h.section()).values()) {
                    try {
                        steps.add(
                                planEntry(
                                        h,
                                        entry,
                                        records.get(entry.key()),
                                        records.values(),
                                        orphans,
                                        renamed,
                                        ctx,
                                        warnings));
                    } catch (IacException | ResponseStatusException e) {
                        problems.add(where(h, entry.key()) + ": " + message(e));
                    } catch (RuntimeException e) {
                        log.warn("Planning {} failed", where(h, entry.key()), e);
                        problems.add(where(h, entry.key()) + ": " + message(e));
                    }
                }
            }
            // Removed entries, last handler first.
            for (ResourceHandler<?> h : handlers.reversed()) {
                for (ManagedResource r : managed.findBySection(h.section())) {
                    if (doc.declared(h.section(), r.getEntryKey())) continue;
                    if (renamed.contains(r.getId())) continue;
                    boolean delete = deletes(h, r);
                    Plan.Action action = delete ? Plan.Action.DELETE : Plan.Action.RELEASE;
                    Plan.Item item =
                            new Plan.Item(
                                    h.section(),
                                    r.getEntryKey(),
                                    action,
                                    List.of(),
                                    r.getLabel(),
                                    r.getHostId());
                    steps.add(new Step(h, null, r, null, action, null, null, item));
                }
            }
        }
        Plan plan = new Plan(steps.stream().map(Step::item).toList(), problems, warnings);
        return new Prepared(doc, steps, plan);
    }

    static String itemKey(String section, String key) {
        return section + "/" + key;
    }

    /**
     * The plan without the changes that failed last time (see ConfigService's periodic check).
     * Re-creating missing things stays in. When anything is left out, removals are too: they only
     * happen once everything else applied.
     */
    Prepared withoutRetries(Prepared p, Set<String> failed) {
        List<Step> kept = new ArrayList<>();
        boolean skipped = false;
        for (Step s : p.steps()) {
            boolean retry =
                    s.action() != Plan.Action.RECREATE
                            && s.action() != Plan.Action.UNCHANGED
                            && failed.contains(itemKey(s.handler().section(), s.item().key()));
            if (retry) skipped = true;
            else kept.add(s);
        }
        if (!skipped) return p;
        boolean finalSkipped = skipped;
        List<Step> steps =
                kept.stream()
                        .filter(
                                s ->
                                        !finalSkipped
                                                || (s.action() != Plan.Action.DELETE
                                                        && s.action() != Plan.Action.RELEASE))
                        .toList();
        Plan plan =
                new Plan(
                        steps.stream().map(Step::item).toList(),
                        p.plan().problems(),
                        p.plan().warnings());
        return new Prepared(p.doc(), steps, plan);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean deletes(ResourceHandler h, ManagedResource r) {
        Object applied = null;
        try {
            if (r.getAppliedSpec() != null) {
                applied = IacJson.SPEC.readValue(r.getAppliedSpec(), h.specType());
            }
        } catch (Exception e) {
            // Written by an older version: fall back to the handler's default.
        }
        return h.deletesOnRemove(r, applied);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Step planEntry(
            ResourceHandler h,
            ResourceHandler.Entry entry,
            ManagedResource record,
            java.util.Collection<ManagedResource> sectionRecords,
            List<ManagedResource> orphans,
            Set<String> renamed,
            Ctx ctx,
            List<String> warnings) {
        h.validate(entry, ctx);
        ObjectNode desired = IacJson.JSON.valueToTree(entry.spec());
        String json = desired.toString();

        if (record != null) {
            boolean exists;
            try {
                exists = h.exists(record, ctx);
            } catch (RuntimeException e) {
                warnings.add(
                        where(h, entry.key())
                                + ": could not check that it still exists ("
                                + message(e)
                                + ")");
                exists = true;
            }
            Plan.Action action;
            Changes changes;
            List<String> fields = List.of();
            if (!exists) {
                action = Plan.Action.RECREATE;
                changes = Changes.all();
            } else {
                Set<String> changed = diff(record.getAppliedSpec(), desired);
                if (changed.isEmpty()) changed = drift(h, entry, record, ctx, warnings);
                action = changed.isEmpty() ? Plan.Action.UNCHANGED : Plan.Action.UPDATE;
                changes = new Changes(changed, false);
                fields = List.copyOf(changed);
            }
            Plan.Item item =
                    new Plan.Item(
                            h.section(),
                            entry.key(),
                            action,
                            fields,
                            record.getLabel(),
                            record.getHostId());
            return new Step(h, entry, record, null, action, changes, json, item);
        }

        Optional<ResourceHandler.Target> existing;
        try {
            existing = h.findExisting(entry, ctx);
        } catch (IacException | ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            warnings.add(
                    where(h, entry.key())
                            + ": could not check whether it already exists ("
                            + message(e)
                            + "); it will be created");
            existing = Optional.empty();
        }
        if (existing.isPresent()) {
            ResourceHandler.Target t = existing.get();
            // An entry whose key changed finds what its old key made: rename the record instead
            // of taking it over and then deleting it as the removed old key.
            for (ManagedResource old : orphans) {
                if (old.getTargetId().equals(t.id())
                        && Objects.equals(old.getHostId(), t.hostId())
                        && !renamed.contains(old.getId())) {
                    renamed.add(old.getId());
                    Plan.Item item =
                            new Plan.Item(
                                    h.section(),
                                    entry.key(),
                                    Plan.Action.UPDATE,
                                    List.of("(renamed from " + old.getEntryKey() + ")"),
                                    old.getLabel(),
                                    old.getHostId());
                    return new Step(
                            h, entry, old, null, Plan.Action.UPDATE, Changes.all(), json, item);
                }
            }
            for (ManagedResource other : sectionRecords) {
                if (other.getTargetId().equals(t.id())
                        && Objects.equals(other.getHostId(), t.hostId())) {
                    throw new IacException(
                            "it describes the same thing as the entry \""
                                    + other.getEntryKey()
                                    + "\"; remove one of them");
                }
            }
            Plan.Item item =
                    new Plan.Item(
                            h.section(),
                            entry.key(),
                            Plan.Action.ADOPT,
                            List.of(),
                            t.label(),
                            t.hostId());
            return new Step(h, entry, null, t, Plan.Action.ADOPT, Changes.all(), json, item);
        }
        Plan.Item item =
                new Plan.Item(h.section(), entry.key(), Plan.Action.CREATE, List.of(), null, null);
        return new Step(h, entry, null, null, Plan.Action.CREATE, Changes.all(), json, item);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<String> drift(
            ResourceHandler h,
            ResourceHandler.Entry entry,
            ManagedResource record,
            ApplyContext ctx,
            List<String> warnings) {
        try {
            Set<String> d = h.drift(entry, record, ctx);
            return d == null ? Set.of() : new TreeSet<>(d);
        } catch (IacException | ResponseStatusException e) {
            warnings.add(where(h, entry.key()) + ": " + message(e));
        } catch (RuntimeException e) {
            warnings.add(
                    where(h, entry.key()) + ": could not check its links (" + message(e) + ")");
        }
        return Set.of();
    }

    /** Top-level fields that differ between what was applied and what the file says now. */
    static Set<String> diff(String appliedJson, ObjectNode desiredTree) {
        Set<String> changed = new TreeSet<>();
        JsonNode applied;
        ObjectNode desired = desiredTree;
        try {
            applied = appliedJson == null ? null : IacJson.JSON.readTree(appliedJson);
            // Compare both as read back from text: a Long field is a LongNode in a fresh tree but
            // an IntNode once stored, and those never count as equal.
            desired = (ObjectNode) IacJson.JSON.readTree(desiredTree.toString());
        } catch (Exception e) {
            applied = null;
        }
        if (!(applied instanceof ObjectNode old)) {
            desired.fieldNames().forEachRemaining(changed::add);
            if (changed.isEmpty()) changed.add("(all)");
            return changed;
        }
        Set<String> names = new HashSet<>();
        desired.fieldNames().forEachRemaining(names::add);
        old.fieldNames().forEachRemaining(names::add);
        for (String n : names) {
            if (!Objects.equals(normal(desired.get(n)), normal(old.get(n)))) changed.add(n);
        }
        return changed;
    }

    private static JsonNode normal(JsonNode n) {
        return n == null || n.isNull() ? null : n;
    }

    /** Carries out a plan. Updates the run as it goes. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void execute(Prepared prepared, ConfigRun run) {
        Ctx ctx = new Ctx(prepared.doc(), false, false, run.startedBy, run.notes);
        Map<Plan.Item, ConfigRun.Item> items = new LinkedHashMap<>();
        for (Step s : prepared.steps()) {
            ConfigRun.Item i = new ConfigRun.Item(s.item());
            if (s.action() == Plan.Action.UNCHANGED) i.status = ConfigRun.ItemStatus.DONE;
            items.put(s.item(), i);
            run.items.add(i);
        }

        boolean failed = false;
        int changed = 0;
        // Creates and updates in section order, then the check of links between entries, then
        // removals (last section first).
        for (Step s : prepared.steps()) {
            if (isRemoval(s.action())) continue;
            ConfigRun.Item item = items.get(s.item());
            if (s.action() == Plan.Action.UNCHANGED) continue;
            if (runStep(s, item, ctx)) changed++;
            else failed = true;
        }

        Ctx settle = new Ctx(prepared.doc(), false, true, run.startedBy, run.notes);
        for (Step s : prepared.steps()) {
            if (isRemoval(s.action()) || s.entry() == null) continue;
            ConfigRun.Item item = items.get(s.item());
            if (item.status != ConfigRun.ItemStatus.DONE) continue;
            switch (settle(s, item, settle, run.warnings)) {
                case 1 -> changed++;
                case -1 -> failed = true;
                default -> {}
            }
        }

        // Removals, last section first. After one fails, sections further down wait: what they
        // remove may still be needed by it (a host by the network that couldn't be removed).
        Integer stopBelow = null;
        String blockedBy = null;
        for (Step s : prepared.steps()) {
            if (!isRemoval(s.action())) continue;
            ConfigRun.Item item = items.get(s.item());
            if (failed) {
                item.status = ConfigRun.ItemStatus.SKIPPED;
                item.message =
                        "Not removed: removals wait until everything else applies without errors";
                continue;
            }
            if (stopBelow != null && s.handler().order() < stopBelow) {
                item.status = ConfigRun.ItemStatus.SKIPPED;
                item.message = "Not removed yet: waits until " + blockedBy + " can be removed";
                continue;
            }
            if (runStep(s, item, ctx)) {
                changed++;
            } else {
                stopBelow = s.handler().order();
                blockedBy = where(s.handler(), s.item().key());
            }
        }
        long failures =
                run.items.stream().filter(i -> i.status == ConfigRun.ItemStatus.FAILED).count();
        run.finish(
                failures == 0
                        ? ConfigRun.Status.SUCCEEDED
                        : changed == 0 ? ConfigRun.Status.FAILED : ConfigRun.Status.PARTIAL);
    }

    private static boolean isRemoval(Plan.Action a) {
        return a == Plan.Action.DELETE || a == Plan.Action.RELEASE;
    }

    /** Carries out one step. False when it failed (the item says why). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean runStep(Step s, ConfigRun.Item item, Ctx ctx) {
        Plan.Action a = s.action();
        item.status = ConfigRun.ItemStatus.RUNNING;
        String where = where(s.handler(), s.item().key());
        try {
            ResourceHandler h = s.handler();
            switch (a) {
                case CREATE, RECREATE -> {
                    ResourceHandler.Target t = h.create(s.entry(), ctx);
                    save(s, t);
                    item.label = t.label();
                }
                case ADOPT -> {
                    ManagedResource r = new ManagedResource();
                    r.setSection(h.section());
                    r.setEntryKey(s.entry().key());
                    r.setTargetId(s.existing().id());
                    r.setHostId(s.existing().hostId());
                    r.setLabel(s.existing().label());
                    ResourceHandler.Target t = h.update(s.entry(), s.changes(), r, ctx);
                    save(s, t);
                    item.label = t.label();
                }
                case UPDATE -> {
                    ResourceHandler.Target t = h.update(s.entry(), s.changes(), s.record(), ctx);
                    save(s, t);
                    item.label = t.label();
                }
                case DELETE -> {
                    h.delete(s.record(), ctx);
                    managed.delete(s.record());
                }
                case RELEASE -> managed.delete(s.record());
                default -> {}
            }
            item.status = ConfigRun.ItemStatus.DONE;
            log.info("Config: {} {}", actionVerb(a), where);
            return true;
        } catch (Exception e) {
            fail(item, e, actionVerb(a), where);
            return false;
        }
    }

    /**
     * After every section is applied: puts back links that are missing (see {@link
     * ResourceHandler#drift}). 1 when something was fixed, 0 when nothing needed it, -1 on failure.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int settle(Step s, ConfigRun.Item item, Ctx ctx, List<String> warnings) {
        ResourceHandler h = s.handler();
        ManagedResource record =
                managed.findBySectionAndEntryKey(h.section(), s.entry().key()).orElse(null);
        if (record == null) return 0;
        String where = where(h, s.item().key());
        Set<String> drift;
        try {
            drift = h.drift(s.entry(), record, ctx);
        } catch (RuntimeException e) {
            // Such as a listed container that doesn't exist: worth saying, not worth failing for.
            warnings.add(where + ": " + message(e));
            return 0;
        }
        if (drift == null || drift.isEmpty()) return 0;
        try {
            ResourceHandler.Target t =
                    h.update(s.entry(), new Changes(new TreeSet<>(drift), false), record, ctx);
            record.setTargetId(t.id());
            record.setHostId(t.hostId());
            record.setLabel(t.label());
            managed.save(record);
            log.info("Config: linked {} ({})", where, String.join(", ", drift));
            return 1;
        } catch (Exception e) {
            fail(item, e, "link", where);
            return -1;
        }
    }

    private static void fail(ConfigRun.Item item, Exception e, String verb, String where) {
        item.status = ConfigRun.ItemStatus.FAILED;
        item.message = message(e);
        if (e instanceof IacException || e instanceof ResponseStatusException) {
            log.warn("Config: could not {} {}: {}", verb, where, item.message);
        } else {
            log.warn("Config: could not {} {}", verb, where, e);
        }
    }

    private void save(Step s, ResourceHandler.Target t) {
        ManagedResource r =
                s.record() != null
                        ? s.record()
                        : managed.findBySectionAndEntryKey(s.handler().section(), s.entry().key())
                                .orElseGet(ManagedResource::new);
        r.setSection(s.handler().section());
        r.setEntryKey(s.entry().key());
        r.setTargetId(t.id());
        r.setHostId(t.hostId());
        r.setLabel(t.label());
        r.setAppliedSpec(s.json());
        r.setAppliedAt(System.currentTimeMillis());
        managed.save(r);
    }

    private static String actionVerb(Plan.Action a) {
        return switch (a) {
            case CREATE -> "create";
            case RECREATE -> "re-create";
            case ADOPT -> "take over";
            case UPDATE -> "update";
            case DELETE -> "delete";
            case RELEASE -> "release";
            case UNCHANGED -> "check";
        };
    }

    static String where(ResourceHandler<?> h, String key) {
        return h.singleton() ? h.section() : h.section() + " \"" + key + "\"";
    }

    static String message(Throwable e) {
        if (e instanceof ResponseStatusException r && r.getReason() != null) return r.getReason();
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** The context handlers see. */
    private class Ctx implements ApplyContext {
        private final ConfigDocument doc;
        private final boolean planning;
        private final boolean settling;
        private final String actor;
        private final List<String> notes;

        Ctx(
                ConfigDocument doc,
                boolean planning,
                boolean settling,
                String actor,
                List<String> notes) {
            this.doc = doc;
            this.planning = planning;
            this.settling = settling;
            this.actor = actor;
            this.notes = notes;
        }

        @Override
        public boolean planning() {
            return planning;
        }

        @Override
        public boolean settling() {
            return settling;
        }

        @Override
        public String hostId(String ref) {
            if (ref != null && !ref.isBlank()) return ref("hosts", ref.trim());
            var declared = doc.entries("hosts").keySet();
            if (declared.size() == 1) return ref("hosts", declared.iterator().next());
            if (declared.isEmpty()) {
                var all = hosts.findAll();
                if (all.size() == 1) return all.getFirst().getId();
                if (all.isEmpty()) {
                    throw new IacException("there are no Docker hosts yet; add one under hosts:");
                }
            }
            throw new IacException(
                    "there are several Docker hosts; say which one with host: <name>");
        }

        @Override
        public String ref(String section, String key) {
            var rec = managed.findBySectionAndEntryKey(section, key);
            if (rec.isPresent()) return rec.get().getTargetId();
            ResourceHandler<?> h = bySection.get(section);
            if (doc.declared(section, key)) {
                if (planning) {
                    // Taken over from something made by hand: found the way the takeover finds
                    // it (a host by its address, not by a name that may belong to another).
                    if (h != null) {
                        Optional<String> found = existingId(h, doc.entries(section).get(key));
                        if (found.isPresent()) return found.get();
                    }
                    return null;
                }
                throw new IacException(
                        section + " \"" + key + "\" was not set up (see the error for it)");
            }
            if (h != null) {
                Optional<String> found = h.lookup(key, this);
                if (found.isPresent()) return found.get();
            }
            throw new IacException(
                    "there is nothing called \""
                            + key
                            + "\" in "
                            + section
                            + " (add it to the file under "
                            + section
                            + ": or check the name)");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Optional<String> existingId(ResourceHandler h, ResourceHandler.Entry entry) {
            if (entry == null) return Optional.empty();
            try {
                Optional<ResourceHandler.Target> t = h.findExisting(entry, this);
                return t.map(ResourceHandler.Target::id);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean declared(String section, String key) {
            return doc.declared(section, key);
        }

        @Override
        public Optional<ManagedResource> record(String section, String key) {
            return managed.findBySectionAndEntryKey(section, key);
        }

        @Override
        public List<ManagedResource> records(String section) {
            return managed.findBySection(section);
        }

        @Override
        public void note(String message) {
            if (planning) return;
            notes.add(message);
            // Nobody is watching a file or scheduled apply: the log is the only place left, as
            // for the first administrator's temporary password.
            if (ConfigService.FILE_ACTOR.equals(actor)
                    || ConfigService.SCHEDULED_ACTOR.equals(actor)) {
                log.info("Config ({}): {}", actor, message);
            }
        }

        @Override
        public String actor() {
            return actor;
        }
    }
}
