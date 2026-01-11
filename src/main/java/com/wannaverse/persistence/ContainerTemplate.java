package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "container_templates")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContainerTemplate {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private String category;

    @Column private String logo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateType type;

    @Column(nullable = false)
    private boolean system;

    @Column private String imageName;

    @Column private String defaultPorts;

    @Column(length = 2000)
    private String defaultEnv;

    @Column(length = 1000)
    private String defaultVolumes;

    @Column private String defaultUser;

    @Column private String defaultNetwork;

    @Column(columnDefinition = "TEXT")
    private String composeContent;

    @Column private String platform;

    @Column private String documentation;

    @Column private long createdAt;

    @Column private long updatedAt;

    @Column private String createdBy;

    public enum TemplateType {
        CONTAINER,
        COMPOSE
    }

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == 0) {
            createdAt = System.currentTimeMillis();
        }
        updatedAt = System.currentTimeMillis();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}
