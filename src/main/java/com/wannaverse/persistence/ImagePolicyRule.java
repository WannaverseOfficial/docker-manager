package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(
        name = "image_policy_rules_t",
        indexes = {@Index(name = "idx_image_policy_rule_policy", columnList = "policy_id")})
public class ImagePolicyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private ImagePolicy policy;

    @Column(nullable = false)
    private String pattern;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private long createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == 0) {
            createdAt = System.currentTimeMillis();
        }
    }

    public ImagePolicyRule(String pattern, String description) {
        this.pattern = pattern;
        this.description = description;
    }
}
