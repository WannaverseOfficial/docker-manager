package com.wannaverse.iac.handlers;

import com.wannaverse.dto.CreateGroupRequest;
import com.wannaverse.dto.UpdateGroupRequest;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.GroupPermission;
import com.wannaverse.persistence.GroupPermissionRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.UserGroup;
import com.wannaverse.persistence.UserGroupRepository;
import com.wannaverse.service.PermissionService;
import com.wannaverse.service.UserService;

import lombok.Data;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@code groups}: user groups and the permissions they give their members. */
@Component
public class GroupsHandler implements ResourceHandler<GroupsHandler.GroupSpec> {

    @Data
    public static class GroupSpec {
        @Doc("The group's name. Defaults to the entry's key.")
        private String name;

        @Doc("What the group is for")
        private String description;

        @Doc("What members may do. Members are set on each user (groups: [...]).")
        private List<PermissionSpec> permissions;
    }

    private final UserGroupRepository groups;
    private final GroupPermissionRepository permissions;
    private final UserService users;
    private final PermissionService permissionService;

    public GroupsHandler(
            UserGroupRepository groups,
            GroupPermissionRepository permissions,
            UserService users,
            PermissionService permissionService) {
        this.groups = groups;
        this.permissions = permissions;
        this.users = users;
        this.permissionService = permissionService;
    }

    @Override
    public String section() {
        return "groups";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Class<GroupSpec> specType() {
        return GroupSpec.class;
    }

    @Override
    public String description() {
        return "User groups and the permissions they give their members";
    }

    @Override
    public void validate(Entry<GroupSpec> entry, ApplyContext ctx) {
        PermissionSpec.validate(list(entry.spec().getPermissions()), ctx);
    }

    @Override
    public Optional<Target> findExisting(Entry<GroupSpec> entry, ApplyContext ctx) {
        return groups.findByName(entry.nameOr(entry.spec().getName())).map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        return groups.findByName(name).map(UserGroup::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return groups.existsById(record.getTargetId());
    }

    @Override
    @Transactional
    public Target create(Entry<GroupSpec> entry, ApplyContext ctx) {
        CreateGroupRequest req = new CreateGroupRequest();
        req.setName(entry.nameOr(entry.spec().getName()));
        req.setDescription(entry.spec().getDescription());
        String id = users.createGroup(req).getId();
        syncPermissions(id, entry.spec(), ctx);
        return target(groups.findById(id).orElseThrow());
    }

    @Override
    @Transactional
    public Target update(
            Entry<GroupSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        String id = record.getTargetId();
        UpdateGroupRequest req = new UpdateGroupRequest();
        req.setName(entry.nameOr(entry.spec().getName()));
        // Taking over keeps what the entry leaves out (description, permissions).
        if (entry.spec().getDescription() != null || !changes.takeover()) {
            req.setDescription(
                    entry.spec().getDescription() == null ? "" : entry.spec().getDescription());
        }
        users.updateGroup(id, req);
        if (changes.has("permissions")
                && (entry.spec().getPermissions() != null || !changes.takeover())) {
            syncPermissions(id, entry.spec(), ctx);
        }
        return target(groups.findById(id).orElseThrow());
    }

    private void syncPermissions(String groupId, GroupSpec spec, ApplyContext ctx) {
        UserGroup group =
                groups.findById(groupId).orElseThrow(() -> new IacException("group not found"));
        Set<PermissionSpec.Grant> wanted = PermissionSpec.grants(list(spec.getPermissions()), ctx);
        for (GroupPermission p : permissions.findByGroupId(groupId)) {
            PermissionSpec.Grant have = grant(p);
            if (!wanted.remove(have)) permissions.delete(p);
        }
        for (PermissionSpec.Grant g : wanted) {
            GroupPermission p = new GroupPermission();
            p.setGroup(group);
            p.setResource(g.resource());
            p.setAction(g.action());
            p.setScopeHostId(g.hostId());
            p.setScopeResourceId(g.resourceId());
            permissions.save(p);
        }
        permissionService.evictPermissionCache();
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (groups.existsById(record.getTargetId())) users.deleteGroup(record.getTargetId());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, GroupSpec> export(ExportContext ctx) {
        Map<String, GroupSpec> out = new LinkedHashMap<>();
        for (UserGroup g : groups.findAll()) {
            GroupSpec s = new GroupSpec();
            s.setDescription(g.getDescription());
            s.setPermissions(
                    PermissionSpec.export(
                            permissions.findByGroupId(g.getId()).stream()
                                    .map(GroupsHandler::grant)
                                    .toList(),
                            ctx));
            String key = ctx.key(section(), g.getId(), g.getName());
            if (!key.equals(g.getName())) s.setName(g.getName());
            out.put(key, s);
        }
        return out;
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static PermissionSpec.Grant grant(GroupPermission p) {
        return new PermissionSpec.Grant(
                p.getResource(), p.getAction(), p.getScopeHostId(), p.getScopeResourceId());
    }

    private Target target(UserGroup g) {
        return new Target(g.getId(), null, g.getName());
    }
}
