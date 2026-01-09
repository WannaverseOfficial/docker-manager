package com.wannaverse.controllers;

import com.wannaverse.persistence.ContainerTemplate;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.CurrentUser;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.TemplateSeederService;
import com.wannaverse.service.TemplateService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final TemplateSeederService seederService;

    public TemplateController(
            TemplateService templateService, TemplateSeederService seederService) {
        this.templateService = templateService;
        this.seederService = seederService;
    }

    @GetMapping
    @RequirePermission(resource = Resource.TEMPLATES, action = "list")
    public ResponseEntity<List<ContainerTemplate>> listTemplates(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean systemOnly,
            @RequestParam(required = false) Boolean userOnly) {

        List<ContainerTemplate> templates;

        if (search != null && !search.isBlank()) {
            templates = templateService.searchTemplates(search);
        } else if (category != null && !category.isBlank()) {
            templates = templateService.getTemplatesByCategory(category);
        } else if (type != null && !type.isBlank()) {
            templates =
                    templateService.getTemplatesByType(
                            ContainerTemplate.TemplateType.valueOf(type));
        } else if (Boolean.TRUE.equals(systemOnly)) {
            templates = templateService.getSystemTemplates();
        } else if (Boolean.TRUE.equals(userOnly)) {
            templates = templateService.getUserTemplates();
        } else {
            templates = templateService.getAllTemplates();
        }

        return ResponseEntity.ok(templates);
    }

    @GetMapping("/categories")
    @RequirePermission(resource = Resource.TEMPLATES, action = "list")
    public ResponseEntity<List<String>> getCategories() {
        return ResponseEntity.ok(templateService.getCategories());
    }

    @GetMapping("/stats")
    @RequirePermission(resource = Resource.TEMPLATES, action = "list")
    public ResponseEntity<Map<String, Long>> getTemplateStats() {
        return ResponseEntity.ok(templateService.getTemplateCountsByCategory());
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.TEMPLATES, action = "read")
    public ResponseEntity<ContainerTemplate> getTemplate(@PathVariable String id) {
        return ResponseEntity.ok(templateService.getTemplate(id));
    }

    @PostMapping
    @RequirePermission(resource = Resource.TEMPLATES, action = "create")
    public ResponseEntity<ContainerTemplate> createTemplate(
            @RequestBody ContainerTemplate template, @CurrentUser String username) {
        ContainerTemplate created = templateService.createTemplate(template, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = Resource.TEMPLATES, action = "update")
    public ResponseEntity<ContainerTemplate> updateTemplate(
            @PathVariable String id,
            @RequestBody ContainerTemplate template,
            @CurrentUser String username) {
        return ResponseEntity.ok(templateService.updateTemplate(id, template, username));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = Resource.TEMPLATES, action = "delete")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate")
    @RequirePermission(resource = Resource.TEMPLATES, action = "create")
    public ResponseEntity<ContainerTemplate> duplicateTemplate(
            @PathVariable String id,
            @RequestParam(required = false) String name,
            @CurrentUser String username) {
        ContainerTemplate duplicate = templateService.duplicateTemplate(id, name, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(duplicate);
    }

    @PostMapping("/reseed")
    @RequirePermission(resource = Resource.TEMPLATES, action = "admin")
    public ResponseEntity<Map<String, Object>> reseedTemplates() {
        int count = seederService.reseedTemplates();
        return ResponseEntity.ok(Map.of("message", "System templates reseeded", "count", count));
    }
}
