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

    @Column private String logo; // Icon name or URL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateType type;

    @Column(nullable = false)
    private boolean system; // true = shipped with app, false = user-created

    // For CONTAINER type
    @Column private String imageName;

    @Column private String defaultPorts; // "5432:5432,5433:5433"

    @Column(length = 2000)
    private String defaultEnv; // "KEY=value,KEY2=value2"

    @Column(length = 1000)
    private String defaultVolumes; // "/host:/container,/host2:/container2"

    @Column private String defaultUser; // "1000:1000"

    @Column private String defaultNetwork;

    // For COMPOSE type
    @Column(columnDefinition = "TEXT")
    private String composeContent;

    @Column private String platform; // "linux", "linux/amd64", etc.

    @Column private String documentation; // URL to docs

    @Column private long createdAt;

    @Column private long updatedAt;

    @Column private String createdBy; // Username or "system"

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
