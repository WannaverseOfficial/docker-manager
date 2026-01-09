package com.wannaverse.service;

import com.wannaverse.persistence.ContainerTemplate;
import com.wannaverse.persistence.ContainerTemplateRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TemplateService {

    private final ContainerTemplateRepository templateRepository;

    public TemplateService(ContainerTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    public List<ContainerTemplate> getAllTemplates() {
        return templateRepository.findAllByOrderByCategoryAscNameAsc();
    }

    public List<ContainerTemplate> getSystemTemplates() {
        return templateRepository.findBySystemTrue();
    }

    public List<ContainerTemplate> getUserTemplates() {
        return templateRepository.findBySystemFalse();
    }

    public List<ContainerTemplate> getTemplatesByCategory(String category) {
        return templateRepository.findByCategory(category);
    }

    public List<ContainerTemplate> getTemplatesByType(ContainerTemplate.TemplateType type) {
        return templateRepository.findByType(type);
    }

    public List<ContainerTemplate> searchTemplates(String query) {
        return templateRepository.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
                query, query);
    }

    public ContainerTemplate getTemplate(String id) {
        return templateRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Template not found"));
    }

    public ContainerTemplate createTemplate(ContainerTemplate template, String username) {
        template.setId(UUID.randomUUID().toString());
        template.setSystem(false); // User templates are never system templates
        template.setCreatedBy(username);
        template.setCreatedAt(System.currentTimeMillis());
        template.setUpdatedAt(System.currentTimeMillis());

        validateTemplate(template);

        return templateRepository.save(template);
    }

    public ContainerTemplate updateTemplate(String id, ContainerTemplate updates, String username) {
        ContainerTemplate existing = getTemplate(id);

        if (existing.isSystem()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "System templates cannot be modified");
        }

        // Update fields
        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
        if (updates.getCategory() != null) {
            existing.setCategory(updates.getCategory());
        }
        if (updates.getLogo() != null) {
            existing.setLogo(updates.getLogo());
        }
        if (updates.getType() != null) {
            existing.setType(updates.getType());
        }
        if (updates.getImageName() != null) {
            existing.setImageName(updates.getImageName());
        }
        if (updates.getDefaultPorts() != null) {
            existing.setDefaultPorts(updates.getDefaultPorts());
        }
        if (updates.getDefaultEnv() != null) {
            existing.setDefaultEnv(updates.getDefaultEnv());
        }
        if (updates.getDefaultVolumes() != null) {
            existing.setDefaultVolumes(updates.getDefaultVolumes());
        }
        if (updates.getDefaultUser() != null) {
            existing.setDefaultUser(updates.getDefaultUser());
        }
        if (updates.getDefaultNetwork() != null) {
            existing.setDefaultNetwork(updates.getDefaultNetwork());
        }
        if (updates.getComposeContent() != null) {
            existing.setComposeContent(updates.getComposeContent());
        }
        if (updates.getPlatform() != null) {
            existing.setPlatform(updates.getPlatform());
        }
        if (updates.getDocumentation() != null) {
            existing.setDocumentation(updates.getDocumentation());
        }

        existing.setUpdatedAt(System.currentTimeMillis());

        validateTemplate(existing);

        return templateRepository.save(existing);
    }

    public void deleteTemplate(String id) {
        ContainerTemplate template = getTemplate(id);

        if (template.isSystem()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "System templates cannot be deleted");
        }

        templateRepository.delete(template);
    }

    /** Duplicate a template (system or user) as a new user template. */
    public ContainerTemplate duplicateTemplate(String id, String newName, String username) {
        ContainerTemplate original = getTemplate(id);

        ContainerTemplate duplicate =
                ContainerTemplate.builder()
                        .id(UUID.randomUUID().toString())
                        .name(newName != null ? newName : original.getName() + " (Copy)")
                        .description(original.getDescription())
                        .category(original.getCategory())
                        .logo(original.getLogo())
                        .type(original.getType())
                        .system(false)
                        .imageName(original.getImageName())
                        .defaultPorts(original.getDefaultPorts())
                        .defaultEnv(original.getDefaultEnv())
                        .defaultVolumes(original.getDefaultVolumes())
                        .defaultUser(original.getDefaultUser())
                        .defaultNetwork(original.getDefaultNetwork())
                        .composeContent(original.getComposeContent())
                        .platform(original.getPlatform())
                        .documentation(original.getDocumentation())
                        .createdAt(System.currentTimeMillis())
                        .updatedAt(System.currentTimeMillis())
                        .createdBy(username)
                        .build();

        return templateRepository.save(duplicate);
    }

    /** Get all unique categories. */
    public List<String> getCategories() {
        return templateRepository.findAll().stream()
                .map(ContainerTemplate::getCategory)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** Get template counts by category. */
    public Map<String, Long> getTemplateCountsByCategory() {
        return templateRepository.findAll().stream()
                .collect(
                        Collectors.groupingBy(
                                ContainerTemplate::getCategory, Collectors.counting()));
    }

    private void validateTemplate(ContainerTemplate template) {
        if (template.getName() == null || template.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template name is required");
        }
        if (template.getCategory() == null || template.getCategory().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Template category is required");
        }
        if (template.getType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template type is required");
        }

        // Validate based on type
        if (template.getType() == ContainerTemplate.TemplateType.CONTAINER) {
            if (template.getImageName() == null || template.getImageName().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Image name is required for container templates");
            }
        } else if (template.getType() == ContainerTemplate.TemplateType.COMPOSE) {
            if (template.getComposeContent() == null || template.getComposeContent().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Compose content is required for compose templates");
            }
        }
    }
}
