package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Table(
        name = "image_policies_t",
        indexes = {
            @Index(name = "idx_image_policy_type", columnList = "policyType"),
            @Index(name = "idx_image_policy_enabled", columnList = "enabled"),
            @Index(name = "idx_image_policy_priority", columnList = "priority")
        })
public class ImagePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyType policyType;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    @OneToMany(
            mappedBy = "policy",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ImagePolicyRule> rules = new ArrayList<>();

    public enum PolicyType {
        ALLOW,
        DENY
    }

    @PrePersist
    protected void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == 0) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }

    public void addRule(ImagePolicyRule rule) {
        rules.add(rule);
        rule.setPolicy(this);
    }

    public void removeRule(ImagePolicyRule rule) {
        rules.remove(rule);
        rule.setPolicy(null);
    }
}
