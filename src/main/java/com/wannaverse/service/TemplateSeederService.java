package com.wannaverse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannaverse.persistence.ContainerTemplate;
import com.wannaverse.persistence.ContainerTemplateRepository;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TemplateSeederService {
    private static final Logger log = LoggerFactory.getLogger(TemplateSeederService.class);
    private static final String TEMPLATES_FILE = "data/system-templates.json";

    private final ContainerTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;

    public TemplateSeederService(
            ContainerTemplateRepository templateRepository, ObjectMapper objectMapper) {
        this.templateRepository = templateRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Brings the system templates in line with the bundled file on every start: existing ones are
     * updated in place (keeping their IDs), new ones are added and retired ones removed. (It used
     * to seed only into an empty table, so fixes to the bundled templates never reached existing
     * installs.) User templates are never touched.
     */
    @PostConstruct
    public void seedTemplates() {
        try {
            List<ContainerTemplate> bundled = loadTemplatesFromJson();
            if (bundled.isEmpty()) return;
            java.util.Map<String, ContainerTemplate> existing = new java.util.HashMap<>();
            for (ContainerTemplate t : templateRepository.findBySystemTrue()) {
                existing.putIfAbsent(t.getName(), t);
            }
            java.util.Set<String> names = new java.util.HashSet<>();
            List<ContainerTemplate> toSave = new ArrayList<>();
            for (ContainerTemplate t : bundled) {
                names.add(t.getName());
                ContainerTemplate current = existing.get(t.getName());
                if (current != null) {
                    t.setId(current.getId());
                    t.setCreatedAt(current.getCreatedAt());
                }
                toSave.add(t);
            }
            templateRepository.saveAll(toSave);
            List<ContainerTemplate> retired =
                    templateRepository.findBySystemTrue().stream()
                            .filter(t -> !names.contains(t.getName()))
                            .toList();
            templateRepository.deleteAll(retired);
            log.info(
                    "System templates up to date ({} bundled, {} retired)",
                    toSave.size(),
                    retired.size());
        } catch (Exception e) {
            log.error("Failed to seed system templates: {}", e.getMessage(), e);
        }
    }

    /**
     * Force re-seed system templates. Deletes existing system templates and reloads from JSON. User
     * templates are preserved.
     *
     * @return number of templates seeded
     */
    public int reseedTemplates() {
        // Delete existing system templates
        List<ContainerTemplate> systemTemplates = templateRepository.findBySystemTrue();
        templateRepository.deleteAll(systemTemplates);
        log.info("Deleted {} existing system templates", systemTemplates.size());

        // Reload from JSON
        List<ContainerTemplate> templates = loadTemplatesFromJson();
        if (!templates.isEmpty()) {
            templateRepository.saveAll(templates);
            log.info("Re-seeded {} system templates", templates.size());
        }

        return templates.size();
    }

    private List<ContainerTemplate> loadTemplatesFromJson() {
        List<ContainerTemplate> templates = new ArrayList<>();

        try {
            ClassPathResource resource = new ClassPathResource(TEMPLATES_FILE);
            if (!resource.exists()) {
                log.warn("Templates file not found: {}", TEMPLATES_FILE);
                return templates;
            }

            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode templatesNode = root.get("templates");

                if (templatesNode == null || !templatesNode.isArray()) {
                    log.warn("No templates array found in {}", TEMPLATES_FILE);
                    return templates;
                }

                for (JsonNode node : templatesNode) {
                    try {
                        ContainerTemplate template = parseTemplate(node);
                        templates.add(template);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to parse template: {}",
                                node.has("name") ? node.get("name").asText() : "unknown",
                                e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load templates from {}: {}", TEMPLATES_FILE, e.getMessage(), e);
        }

        return templates;
    }

    private ContainerTemplate parseTemplate(JsonNode node) {
        return ContainerTemplate.builder()
                .id(UUID.randomUUID().toString())
                .name(getTextOrNull(node, "name"))
                .description(getTextOrNull(node, "description"))
                .category(getTextOrNull(node, "category"))
                .logo(getTextOrNull(node, "logo"))
                .type(parseTemplateType(getTextOrNull(node, "type")))
                .system(true)
                .imageName(getTextOrNull(node, "imageName"))
                .defaultPorts(getTextOrNull(node, "defaultPorts"))
                .defaultEnv(getTextOrNull(node, "defaultEnv"))
                .defaultVolumes(getTextOrNull(node, "defaultVolumes"))
                .defaultUser(getTextOrNull(node, "defaultUser"))
                .defaultNetwork(getTextOrNull(node, "defaultNetwork"))
                .composeContent(getTextOrNull(node, "composeContent"))
                .platform(getTextOrNull(node, "platform"))
                .documentation(getTextOrNull(node, "documentation"))
                .createdAt(System.currentTimeMillis())
                .updatedAt(System.currentTimeMillis())
                .createdBy("system")
                .build();
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() ? fieldNode.asText() : null;
    }

    private ContainerTemplate.TemplateType parseTemplateType(String type) {
        if (type == null) {
            return ContainerTemplate.TemplateType.CONTAINER;
        }
        try {
            return ContainerTemplate.TemplateType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ContainerTemplate.TemplateType.CONTAINER;
        }
    }
}
