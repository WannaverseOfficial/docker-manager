package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.ContainerTemplate;
import com.wannaverse.persistence.ContainerTemplate.TemplateType;
import com.wannaverse.persistence.ContainerTemplateRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.service.TemplateService;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code templates}: your own container and compose templates. The built-in templates are never
 * touched.
 */
@Component
public class TemplatesHandler implements ResourceHandler<TemplatesHandler.TemplateSpec> {

    @Data
    @Doc("A template people can start containers or stacks from")
    public static class TemplateSpec {
        @Doc("The template's name. Defaults to the entry's key.")
        private String name;

        @Doc("container (one image) or compose (a compose file). Default: container.")
        private TemplateType type;

        @Doc("What it is")
        private String description;

        @Doc("Category it is listed under (default: Custom)")
        private String category;

        @Doc("Address of a logo image")
        private String logo;

        @Doc("Image to run (container templates)")
        private String image;

        @Doc("Ports, as \"8080:80\" (container templates)")
        private List<String> ports = new ArrayList<>();

        @Doc("Environment variables, as \"KEY=value\" (container templates)")
        private List<String> env = new ArrayList<>();

        @Doc("Volumes, as \"name:/path\" (container templates)")
        private List<String> volumes = new ArrayList<>();

        @Doc("User to run as (container templates)")
        private String user;

        @Doc("Network to join (container templates)")
        private String network;

        @Doc("The compose file (compose templates)")
        private String compose;

        @Doc("Platform, such as linux/amd64")
        private String platform;

        @Doc("Address of the documentation")
        private String documentation;
    }

    private final ContainerTemplateRepository templates;
    private final TemplateService service;

    public TemplatesHandler(ContainerTemplateRepository templates, TemplateService service) {
        this.templates = templates;
        this.service = service;
    }

    @Override
    public String section() {
        return "templates";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public Class<TemplateSpec> specType() {
        return TemplateSpec.class;
    }

    @Override
    public String description() {
        return "Your own container and compose templates (built-in ones are not affected)";
    }

    private static TemplateType type(TemplateSpec s) {
        return s.getType() == null ? TemplateType.CONTAINER : s.getType();
    }

    @Override
    public void validate(Entry<TemplateSpec> entry, ApplyContext ctx) {
        TemplateSpec s = entry.spec();
        if (type(s) == TemplateType.CONTAINER && blank(s.getImage())) {
            throw new IacException("image: a container template needs an image");
        }
        if (type(s) == TemplateType.COMPOSE && blank(s.getCompose())) {
            throw new IacException("compose: a compose template needs a compose file");
        }
        for (List<String> list : List.of(s.getPorts(), s.getEnv(), s.getVolumes())) {
            for (String v : list) {
                if (v != null && v.contains(",")) {
                    throw new IacException("\"" + v + "\": values can't contain commas");
                }
            }
        }
    }

    /** Custom templates with this name; adopted only when there is exactly one. */
    private List<ContainerTemplate> custom(String name) {
        return templates.findBySystemFalse().stream()
                .filter(t -> name.equals(t.getName()))
                .toList();
    }

    @Override
    public Optional<Target> findExisting(Entry<TemplateSpec> entry, ApplyContext ctx) {
        List<ContainerTemplate> found = custom(entry.nameOr(entry.spec().getName()));
        if (found.size() > 1) {
            throw new IacException(
                    "several of your templates are called \""
                            + found.getFirst().getName()
                            + "\"; rename or delete the extra ones first");
        }
        return found.stream().findFirst().map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        List<ContainerTemplate> found = custom(name);
        return found.size() == 1 ? Optional.of(found.getFirst().getId()) : Optional.empty();
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return templates.findById(record.getTargetId()).filter(t -> !t.isSystem()).isPresent();
    }

    @Override
    public Target create(Entry<TemplateSpec> entry, ApplyContext ctx) {
        ContainerTemplate t = new ContainerTemplate();
        fill(t, entry);
        return target(service.createTemplate(t, ctx.actor()));
    }

    @Override
    public Target update(
            Entry<TemplateSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        ContainerTemplate t =
                templates
                        .findById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the template no longer exists"));
        if (t.isSystem()) throw new IacException("built-in templates can't be changed");
        // Saved directly: the service keeps fields the update leaves empty, but removing a
        // setting from the file must clear it.
        fill(t, entry);
        t.setUpdatedAt(System.currentTimeMillis());
        return target(templates.save(t));
    }

    private static void fill(ContainerTemplate t, Entry<TemplateSpec> entry) {
        TemplateSpec s = entry.spec();
        t.setName(entry.nameOr(s.getName()));
        t.setType(type(s));
        t.setDescription(s.getDescription());
        t.setCategory(blank(s.getCategory()) ? "Custom" : s.getCategory().trim());
        t.setLogo(s.getLogo());
        t.setImageName(s.getImage());
        t.setDefaultPorts(join(s.getPorts()));
        t.setDefaultEnv(join(s.getEnv()));
        t.setDefaultVolumes(join(s.getVolumes()));
        t.setDefaultUser(s.getUser());
        t.setDefaultNetwork(s.getNetwork());
        t.setComposeContent(s.getCompose());
        t.setPlatform(s.getPlatform());
        t.setDocumentation(s.getDocumentation());
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        templates
                .findById(record.getTargetId())
                .filter(t -> !t.isSystem())
                .ifPresent(t -> service.deleteTemplate(t.getId()));
    }

    @Override
    public Map<String, TemplateSpec> export(ExportContext ctx) {
        Map<String, TemplateSpec> out = new LinkedHashMap<>();
        for (ContainerTemplate t : service.getUserTemplates()) {
            String base = ctx.key(section(), t.getId(), t.getName());
            String key = base;
            for (int i = 2; out.containsKey(key); i++) key = base + "-" + i;
            TemplateSpec s = new TemplateSpec();
            if (!key.equals(t.getName())) s.setName(t.getName());
            s.setType(t.getType());
            s.setDescription(t.getDescription());
            if (!"Custom".equals(t.getCategory())) s.setCategory(t.getCategory());
            s.setLogo(t.getLogo());
            s.setImage(t.getImageName());
            s.setPorts(split(t.getDefaultPorts()));
            s.setEnv(split(t.getDefaultEnv()));
            s.setVolumes(split(t.getDefaultVolumes()));
            s.setUser(t.getDefaultUser());
            s.setNetwork(t.getDefaultNetwork());
            s.setCompose(t.getComposeContent());
            s.setPlatform(t.getPlatform());
            s.setDocumentation(t.getDocumentation());
            out.put(key, s);
        }
        return out;
    }

    private static String join(List<String> values) {
        List<String> clean =
                values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
        return clean.isEmpty() ? null : String.join(",", clean);
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        return new ArrayList<>(
                Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private Target target(ContainerTemplate t) {
        return new Target(t.getId(), null, t.getName());
    }
}
