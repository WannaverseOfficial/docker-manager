package com.wannaverse.iac.handlers;

import com.wannaverse.dto.CreateUserRequest;
import com.wannaverse.dto.UpdateUserRequest;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.IacJson;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.persistence.NotificationEventType;
import com.wannaverse.persistence.RefreshTokenRepository;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserGroup;
import com.wannaverse.persistence.UserGroupRepository;
import com.wannaverse.persistence.UserNotificationPreference;
import com.wannaverse.persistence.UserNotificationPreferenceRepository;
import com.wannaverse.persistence.UserPermission;
import com.wannaverse.persistence.UserPermissionRepository;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.UserStatusCache;
import com.wannaverse.service.PermissionService;
import com.wannaverse.service.UserService;

import lombok.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** {@code users}: accounts, their groups, permissions and email notifications. */
@Component
public class UsersHandler implements ResourceHandler<UsersHandler.UserSpec> {

    private static final Logger log = LoggerFactory.getLogger(UsersHandler.class);

    @Data
    public static class UserSpec {
        @Doc("Sign-in name. Defaults to the entry's key.")
        private String username;

        @Doc("Email address, for notifications")
        private String email;

        @Doc(
                "Password. Left out: a new user gets a temporary one (shown once after applying)"
                        + " and an existing user keeps theirs.")
        private Secret password;

        @Doc(
                "Ask for a new password at the next sign-in. Default: true for a generated"
                        + " password, false for one set here.")
        private Boolean mustChangePassword;

        @Doc("Full access to everything (default false)")
        private Boolean admin;

        @Doc("Can sign in (default true)")
        private Boolean enabled;

        @Doc("Groups (keys of groups:, or names of groups made by hand)")
        private List<String> groups;

        @Doc("Permissions of this user alone, besides those of their groups")
        private List<PermissionSpec> permissions;

        @Doc(
                "Email notifications to turn on or off, such as deployment-failed: false. Not"
                        + " listed: on. Listing any locks the user's own notification settings.")
        private Map<String, Boolean> notifications;
    }

    private final UserRepository users;
    private final UserGroupRepository groups;
    private final UserPermissionRepository permissions;
    private final UserNotificationPreferenceRepository preferences;
    private final RefreshTokenRepository tokens;
    private final UserService userService;
    private final PermissionService permissionService;
    private final UserStatusCache statusCache;
    private final PasswordEncoder encoder;
    private final ManagedResourceRepository managed;

    public UsersHandler(
            UserRepository users,
            UserGroupRepository groups,
            UserPermissionRepository permissions,
            UserNotificationPreferenceRepository preferences,
            RefreshTokenRepository tokens,
            UserService userService,
            PermissionService permissionService,
            UserStatusCache statusCache,
            PasswordEncoder encoder,
            ManagedResourceRepository managed) {
        this.users = users;
        this.groups = groups;
        this.permissions = permissions;
        this.preferences = preferences;
        this.tokens = tokens;
        this.userService = userService;
        this.permissionService = permissionService;
        this.statusCache = statusCache;
        this.encoder = encoder;
        this.managed = managed;
    }

    @Override
    public String section() {
        return "users";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Class<UserSpec> specType() {
        return UserSpec.class;
    }

    @Override
    public String description() {
        return "User accounts, keyed by user name";
    }

    @Override
    public void validate(Entry<UserSpec> entry, ApplyContext ctx) {
        UserSpec s = entry.spec();
        String name = entry.nameOr(s.getUsername());
        if (name.length() > 100) throw new IacException("username: at most 100 characters");
        if (s.getPassword() != null
                && s.getPassword().value() != null
                && s.getPassword().value().length() < 8) {
            throw new IacException("password: use at least 8 characters");
        }
        for (String g : list(s.getGroups())) ctx.ref("groups", g);
        PermissionSpec.validate(list(s.getPermissions()), ctx);
        events(s.getNotifications());
    }

    @Override
    public Optional<Target> findExisting(Entry<UserSpec> entry, ApplyContext ctx) {
        return users.findByUsername(entry.nameOr(entry.spec().getUsername())).map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        return users.findByUsername(name).map(User::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return users.existsById(record.getTargetId());
    }

    @Override
    @Transactional
    public Target create(Entry<UserSpec> entry, ApplyContext ctx) {
        UserSpec s = entry.spec();
        String name = entry.nameOr(s.getUsername());
        boolean generated = s.getPassword() == null || s.getPassword().isBlank();
        String password = generated ? userService.generateTempPassword() : s.getPassword().value();

        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(name);
        req.setEmail(blankToNull(s.getEmail()));
        req.setAdmin(Boolean.TRUE.equals(s.getAdmin()));
        String id = userService.createUser(req, password).getId();

        User user = users.findById(id).orElseThrow();
        user.setMustChangePassword(
                s.getMustChangePassword() != null ? s.getMustChangePassword() : generated);
        user.setEnabled(s.getEnabled() == null || s.getEnabled());
        users.save(user);
        if (generated) ctx.note("User " + name + ": temporary password " + password);
        syncGroups(user, s, ctx);
        syncPermissions(user, s, ctx);
        syncNotifications(user, s);
        statusCache.evict(id);
        return target(user);
    }

    @Override
    @Transactional
    public Target update(
            Entry<UserSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        UserSpec s = entry.spec();
        String id = record.getTargetId();
        UpdateUserRequest req = new UpdateUserRequest();
        req.setUsername(entry.nameOr(s.getUsername()));
        // Taking over an existing user changes only what the entry says; what it leaves out
        // (admin, groups, permissions...) stays as it is until the entry mentions it.
        boolean takeover = changes.takeover();
        if (changes.has("email") && s.getEmail() != null) req.setEmail(s.getEmail().trim());
        if (s.getAdmin() != null || !takeover) req.setAdmin(Boolean.TRUE.equals(s.getAdmin()));
        if (s.getEnabled() != null || !takeover) {
            req.setEnabled(s.getEnabled() == null || s.getEnabled());
        }
        userService.updateUser(id, req);

        User user = users.findById(id).orElseThrow();
        if (!takeover && changes.has("email") && blankToNull(s.getEmail()) == null) {
            user.setEmail(null);
        }
        if (changes.has("password") && s.getPassword() != null && !s.getPassword().isBlank()) {
            user.setPasswordHash(encoder.encode(s.getPassword().value()));
            user.setMustChangePassword(Boolean.TRUE.equals(s.getMustChangePassword()));
            tokens.revokeAllForUser(id);
        } else if (changes.has("mustChangePassword") && s.getMustChangePassword() != null) {
            user.setMustChangePassword(s.getMustChangePassword());
        }
        users.save(user);
        if (changes.has("groups") && (s.getGroups() != null || !takeover)) {
            syncGroups(user, s, ctx);
        }
        if (changes.has("permissions") && (s.getPermissions() != null || !takeover)) {
            syncPermissions(user, s, ctx);
        }
        if (changes.has("notifications")) syncNotifications(user, s);
        statusCache.evict(id);
        return target(user);
    }

    private void syncGroups(User user, UserSpec s, ApplyContext ctx) {
        Set<String> wanted = new HashSet<>();
        for (String g : list(s.getGroups())) wanted.add(ctx.ref("groups", g));
        user.getGroups().removeIf(g -> !wanted.contains(g.getId()));
        Set<String> have =
                user.getGroups().stream().map(UserGroup::getId).collect(Collectors.toSet());
        for (String gid : wanted) {
            if (!have.contains(gid)) user.getGroups().add(groups.findById(gid).orElseThrow());
        }
        users.save(user);
        permissionService.evictPermissionCache();
    }

    private void syncPermissions(User user, UserSpec s, ApplyContext ctx) {
        Set<PermissionSpec.Grant> wanted = PermissionSpec.grants(list(s.getPermissions()), ctx);
        for (UserPermission p : permissions.findByUserId(user.getId())) {
            if (!wanted.remove(grant(p))) permissions.delete(p);
        }
        for (PermissionSpec.Grant g : wanted) {
            UserPermission p = new UserPermission();
            p.setUser(user);
            p.setResource(g.resource());
            p.setAction(g.action());
            p.setScopeHostId(g.hostId());
            p.setScopeResourceId(g.resourceId());
            permissions.save(p);
        }
        permissionService.evictPermissionCache();
    }

    private void syncNotifications(User user, UserSpec s) {
        if (s.getNotifications() == null) return;
        Map<NotificationEventType, Boolean> wanted = events(s.getNotifications());
        for (UserNotificationPreference p : preferences.findByUserId(user.getId())) {
            Boolean v = wanted.remove(p.getEventType());
            if (v == null) {
                preferences.delete(p);
            } else {
                p.setEmailEnabled(v);
                preferences.save(p);
            }
        }
        wanted.forEach(
                (type, on) -> {
                    UserNotificationPreference p = new UserNotificationPreference();
                    p.setUser(user);
                    p.setEventType(type);
                    p.setEmailEnabled(on);
                    preferences.save(p);
                });
    }

    private static Map<NotificationEventType, Boolean> events(Map<String, Boolean> in) {
        Map<NotificationEventType, Boolean> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (var e : in.entrySet()) {
            String norm = e.getKey().trim().toUpperCase(Locale.ROOT).replace('-', '_');
            NotificationEventType type =
                    Arrays.stream(NotificationEventType.values())
                            .filter(t -> t.name().equals(norm))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IacException(
                                                    "notifications: unknown event \""
                                                            + e.getKey()
                                                            + "\". Events: "
                                                            + Arrays.stream(
                                                                            NotificationEventType
                                                                                    .values())
                                                                    .map(IacJson::enumName)
                                                                    .collect(
                                                                            Collectors.joining(
                                                                                    ", "))));
            out.put(type, e.getValue() == null || e.getValue());
        }
        return out;
    }

    @Override
    @Transactional
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (users.existsById(record.getTargetId())) userService.deleteUser(record.getTargetId());
    }

    /** Whether the file sets this user's notifications, so the user can't change them. */
    public boolean managesNotifications(String userId) {
        return managed.findBySectionAndTargetId(section(), userId).stream()
                .anyMatch(
                        r -> {
                            try {
                                var spec =
                                        IacJson.SPEC.readValue(r.getAppliedSpec(), UserSpec.class);
                                return spec.getNotifications() != null;
                            } catch (Exception e) {
                                return false;
                            }
                        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UserSpec> export(ExportContext ctx) {
        Map<String, UserSpec> out = new LinkedHashMap<>();
        for (User u : users.findAll()) {
            UserSpec s = new UserSpec();
            s.setEmail(u.getEmail());
            if (u.isAdmin()) s.setAdmin(true);
            if (!u.isEnabled()) s.setEnabled(false);
            s.setGroups(
                    u.getGroups().stream()
                            .map(g -> ctx.key("groups", g.getId(), g.getName()))
                            .sorted()
                            .toList());
            s.setPermissions(
                    PermissionSpec.export(
                            permissions.findByUserId(u.getId()).stream()
                                    .map(UsersHandler::grant)
                                    .toList(),
                            ctx));
            var prefs = preferences.findByUserId(u.getId());
            if (!prefs.isEmpty()) {
                Map<String, Boolean> n = new LinkedHashMap<>();
                for (var p : prefs) n.put(IacJson.enumName(p.getEventType()), p.isEmailEnabled());
                s.setNotifications(n);
            }
            String key = ctx.key(section(), u.getId(), u.getUsername());
            if (!key.equals(u.getUsername())) s.setUsername(u.getUsername());
            out.put(key, s);
        }
        return out;
    }

    private static PermissionSpec.Grant grant(UserPermission p) {
        return new PermissionSpec.Grant(
                p.getResource(), p.getAction(), p.getScopeHostId(), p.getScopeResourceId());
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Target target(User u) {
        return new Target(u.getId(), null, u.getUsername());
    }
}
