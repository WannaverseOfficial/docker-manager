package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.IacJson;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.ResourceActions;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** A permission granted to a user or a group, as written in the file. */
@Data
@Doc("Lets the user or group do these actions on this kind of resource")
public class PermissionSpec {
    @Doc(
            value =
                    "The kind of resource, such as containers, stacks or git-repos. databases,"
                            + " security, observability, configuration and dns are for"
                            + " administrators only and can't be granted.",
            required = true)
    private Resource resource;

    @Doc(value = "Actions, such as [list, read, start, stop], or [\"*\"] for all", required = true)
    private List<String> actions = new ArrayList<>();

    @Doc("Only on this host (a key of hosts:, or a host's name). Default: every host.")
    private String host;

    @Doc("Only on this one resource (its id or name). Default: all of them.")
    private String id;

    /** One granted row. */
    public record Grant(Resource resource, String action, String hostId, String resourceId) {}

    /** Checks the actions; while planning a host this apply adds gives a null host id. */
    public static void validate(List<PermissionSpec> specs, ApplyContext ctx) {
        for (PermissionSpec p : specs) {
            Set<String> valid = ResourceActions.ACTIONS.get(p.getResource());
            if (valid == null) {
                throw new IacException(
                        "permissions: "
                                + IacJson.enumName(p.getResource())
                                + " can't be granted; only administrators use it");
            }
            for (String a : p.getActions()) {
                if (!a.equals("*") && !valid.contains(a)) {
                    throw new IacException(
                            "permissions: \""
                                    + a
                                    + "\" is not an action on "
                                    + IacJson.enumName(p.getResource())
                                    + ". Actions: "
                                    + String.join(", ", new TreeSet<>(valid)));
                }
            }
            if (p.getHost() != null) ctx.hostId(p.getHost());
        }
    }

    /** The rows these entries grant. */
    public static Set<Grant> grants(List<PermissionSpec> specs, ApplyContext ctx) {
        Set<Grant> out = new LinkedHashSet<>();
        for (PermissionSpec p : specs) {
            String hostId = p.getHost() == null ? null : ctx.hostId(p.getHost());
            Set<String> actions = new TreeSet<>();
            for (String a : p.getActions()) {
                if (a.equals("*")) actions.addAll(ResourceActions.ACTIONS.get(p.getResource()));
                else actions.add(a);
            }
            for (String a : actions) {
                out.add(new Grant(p.getResource(), a, hostId, blankToNull(p.getId())));
            }
        }
        return out;
    }

    /** Groups granted rows back into entries (one per resource, host and id). */
    public static List<PermissionSpec> export(List<Grant> grants, ExportContext ctx) {
        Map<List<Object>, PermissionSpec> byScope = new LinkedHashMap<>();
        for (Grant g : grants) {
            List<Object> scope = new ArrayList<>();
            scope.add(g.resource());
            scope.add(Objects.toString(g.hostId(), ""));
            scope.add(Objects.toString(g.resourceId(), ""));
            PermissionSpec p =
                    byScope.computeIfAbsent(
                            scope,
                            k -> {
                                PermissionSpec n = new PermissionSpec();
                                n.setResource(g.resource());
                                n.setHost(g.hostId() == null ? null : ctx.hostKey(g.hostId()));
                                n.setId(g.resourceId());
                                return n;
                            });
            p.getActions().add(g.action());
        }
        for (PermissionSpec p : byScope.values()) {
            Set<String> all = ResourceActions.ACTIONS.get(p.getResource());
            if (all != null && new TreeSet<>(p.getActions()).equals(new TreeSet<>(all))) {
                p.setActions(new ArrayList<>(List.of("*")));
            } else {
                p.setActions(new ArrayList<>(new TreeSet<>(p.getActions())));
            }
        }
        return new ArrayList<>(byScope.values());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
