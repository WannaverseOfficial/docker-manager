package com.wannaverse.service;

import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ImagePolicyService {
    private static final Logger log = LoggerFactory.getLogger(ImagePolicyService.class);

    private final ImagePolicyRepository policyRepository;
    private final ImagePolicyRuleRepository ruleRepository;

    public ImagePolicyService(
            ImagePolicyRepository policyRepository, ImagePolicyRuleRepository ruleRepository) {
        this.policyRepository = policyRepository;
        this.ruleRepository = ruleRepository;
    }

    // CRUD Operations

    public List<ImagePolicy> getAllPolicies() {
        return policyRepository.findAllByOrderByPriorityAsc();
    }

    public Optional<ImagePolicy> getPolicy(String id) {
        return policyRepository.findByIdWithRules(id);
    }

    @Transactional
    public ImagePolicy createPolicy(
            String name,
            String description,
            ImagePolicy.PolicyType policyType,
            int priority,
            boolean enabled) {

        if (policyRepository.existsByName(name)) {
            throw new IllegalArgumentException("Policy with name '" + name + "' already exists");
        }

        ImagePolicy policy = new ImagePolicy();
        policy.setName(name);
        policy.setDescription(description);
        policy.setPolicyType(policyType);
        policy.setPriority(priority);
        policy.setEnabled(enabled);

        return policyRepository.save(policy);
    }

    @Transactional
    public ImagePolicy updatePolicy(
            String id, String name, String description, int priority, boolean enabled) {

        ImagePolicy policy =
                policyRepository
                        .findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + id));

        if (!policy.getName().equals(name) && policyRepository.existsByName(name)) {
            throw new IllegalArgumentException("Policy with name '" + name + "' already exists");
        }

        policy.setName(name);
        policy.setDescription(description);
        policy.setPriority(priority);
        policy.setEnabled(enabled);

        return policyRepository.save(policy);
    }

    @Transactional
    public void deletePolicy(String id) {
        if (!policyRepository.existsById(id)) {
            throw new IllegalArgumentException("Policy not found: " + id);
        }
        policyRepository.deleteById(id);
    }

    @Transactional
    public ImagePolicyRule addRule(String policyId, String pattern, String description) {
        ImagePolicy policy =
                policyRepository
                        .findByIdWithRules(policyId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Policy not found: " + policyId));

        validatePattern(pattern);

        ImagePolicyRule rule = new ImagePolicyRule(pattern, description);
        policy.addRule(rule);
        policyRepository.save(policy);

        return rule;
    }

    @Transactional
    public void removeRule(String policyId, String ruleId) {
        ImagePolicy policy =
                policyRepository
                        .findByIdWithRules(policyId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Policy not found: " + policyId));

        ImagePolicyRule rule =
                policy.getRules().stream()
                        .filter(r -> r.getId().equals(ruleId))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("Rule not found: " + ruleId));

        policy.removeRule(rule);
        policyRepository.save(policy);
    }

    // Validation Methods

    public boolean isImageAllowed(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return false;
        }

        String normalizedImage = normalizeImageName(imageName);

        // Check DENY policies first
        List<ImagePolicy> denyPolicies =
                policyRepository.findByTypeEnabledWithRules(ImagePolicy.PolicyType.DENY);

        for (ImagePolicy policy : denyPolicies) {
            if (matchesAnyRule(normalizedImage, policy.getRules())) {
                log.info(
                        "Image '{}' denied by policy '{}' (type: DENY)",
                        imageName,
                        policy.getName());
                return false;
            }
        }

        // Check ALLOW policies
        List<ImagePolicy> allowPolicies =
                policyRepository.findByTypeEnabledWithRules(ImagePolicy.PolicyType.ALLOW);

        // If no ALLOW policies exist, allow everything not denied
        if (allowPolicies.isEmpty()) {
            log.debug("No ALLOW policies defined, image '{}' is allowed", imageName);
            return true;
        }

        // Check if image matches any ALLOW policy
        for (ImagePolicy policy : allowPolicies) {
            if (matchesAnyRule(normalizedImage, policy.getRules())) {
                log.debug("Image '{}' allowed by policy '{}'", imageName, policy.getName());
                return true;
            }
        }

        log.info("Image '{}' denied - not in any ALLOW list (no matching allow policy)", imageName);
        return false;
    }

    public ValidationResult validateImage(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return new ValidationResult(false, "Image name is empty", null);
        }

        String normalizedImage = normalizeImageName(imageName);

        // Check DENY policies first
        List<ImagePolicy> denyPolicies =
                policyRepository.findByTypeEnabledWithRules(ImagePolicy.PolicyType.DENY);

        for (ImagePolicy policy : denyPolicies) {
            String matchedPattern = findMatchingPattern(normalizedImage, policy.getRules());
            if (matchedPattern != null) {
                return new ValidationResult(
                        false,
                        "Image matches DENY rule '"
                                + matchedPattern
                                + "' in policy '"
                                + policy.getName()
                                + "'",
                        policy.getName());
            }
        }

        // Check ALLOW policies
        List<ImagePolicy> allowPolicies =
                policyRepository.findByTypeEnabledWithRules(ImagePolicy.PolicyType.ALLOW);

        if (allowPolicies.isEmpty()) {
            return new ValidationResult(true, "No restrictions configured", null);
        }

        for (ImagePolicy policy : allowPolicies) {
            String matchedPattern = findMatchingPattern(normalizedImage, policy.getRules());
            if (matchedPattern != null) {
                return new ValidationResult(
                        true,
                        "Image allowed by pattern '"
                                + matchedPattern
                                + "' in policy '"
                                + policy.getName()
                                + "'",
                        policy.getName());
            }
        }

        return new ValidationResult(false, "Image not in any ALLOW list", null);
    }

    public void validateImageForPull(String imageName) {
        ValidationResult result = validateImage(imageName);
        if (!result.allowed()) {
            throw new ImagePolicyViolationException(
                    imageName, result.policyName(), result.reason());
        }
    }

    public void validateImageForContainerCreation(String imageName) {
        ValidationResult result = validateImage(imageName);
        if (!result.allowed()) {
            throw new ImagePolicyViolationException(
                    imageName, result.policyName(), result.reason());
        }
    }

    // Helper Methods

    private boolean matchesAnyRule(String imageName, List<ImagePolicyRule> rules) {
        for (ImagePolicyRule rule : rules) {
            if (matchesPattern(imageName, rule.getPattern())) {
                return true;
            }
        }
        return false;
    }

    private String findMatchingPattern(String imageName, List<ImagePolicyRule> rules) {
        for (ImagePolicyRule rule : rules) {
            if (matchesPattern(imageName, rule.getPattern())) {
                return rule.getPattern();
            }
        }
        return null;
    }

    private boolean matchesPattern(String imageName, String pattern) {
        // Convert glob-like pattern to regex
        // * matches anything
        // ? matches single character
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");

        try {
            return Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE)
                    .matcher(imageName)
                    .matches();
        } catch (Exception e) {
            log.warn("Invalid pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    private String normalizeImageName(String imageName) {
        // Normalize image name:
        // - Add "library/" prefix for official images without namespace
        // - Add ":latest" tag if no tag specified
        // - Add "docker.io/" prefix if no registry specified

        String normalized = imageName.trim();

        // Check if has registry (contains . or :port before first /)
        boolean hasRegistry = false;
        int firstSlash = normalized.indexOf('/');
        if (firstSlash > 0) {
            String possibleRegistry = normalized.substring(0, firstSlash);
            hasRegistry = possibleRegistry.contains(".") || possibleRegistry.contains(":");
        }

        // If no registry, it's a Docker Hub image
        if (!hasRegistry) {
            // Check if it's an official image (no /)
            if (!normalized.contains("/")) {
                normalized = "library/" + normalized;
            }
            normalized = "docker.io/" + normalized;
        }

        // Add :latest if no tag
        if (!normalized.contains(":")
                || normalized.lastIndexOf(':') < normalized.lastIndexOf('/')) {
            normalized = normalized + ":latest";
        }

        return normalized;
    }

    private void validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern cannot be empty");
        }

        // Basic validation - try to compile as regex
        try {
            String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
            Pattern.compile("^" + regex + "$");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid pattern: " + e.getMessage());
        }
    }

    // Inner Classes

    public record ValidationResult(boolean allowed, String reason, String policyName) {}

    public static class ImagePolicyViolationException extends RuntimeException {
        private final String imageName;
        private final String policyName;
        private final String reason;

        public ImagePolicyViolationException(String imageName, String policyName, String reason) {
            super("Image policy violation: " + reason);
            this.imageName = imageName;
            this.policyName = policyName;
            this.reason = reason;
        }

        public String getImageName() {
            return imageName;
        }

        public String getPolicyName() {
            return policyName;
        }

        public String getReason() {
            return reason;
        }
    }
}
