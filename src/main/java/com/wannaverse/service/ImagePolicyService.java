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

    /**
     * Glob match ("*" any run of characters, "?" one character) against the canonical image name.
     * Patterns are written the way people write images, so they are canonicalised the same way:
     * "nginx*" means "docker.io/library/nginx*" and "bitnami/*" means "docker.io/bitnami/*".
     * Patterns starting with a wildcard are used as written.
     */
    static boolean matchesPattern(String imageName, String pattern) {
        String p = pattern.trim();
        if (!p.startsWith("*") && !p.startsWith("?")) {
            int slash = p.indexOf('/');
            String first = slash > 0 ? p.substring(0, slash) : null;
            boolean hasRegistry =
                    first != null
                            && (first.contains(".")
                                    || first.contains(":")
                                    || first.equals("localhost"));
            if (!hasRegistry) {
                p = "docker.io/" + (p.contains("/") ? p : "library/" + p);
            } else if (first.equalsIgnoreCase("index.docker.io")
                    || first.equalsIgnoreCase("registry-1.docker.io")) {
                p = "docker.io" + p.substring(slash);
            }
        }
        StringBuilder regex = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (char c : p.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) regex.append(Pattern.quote(literal.toString()));
        regex.append("$");
        // A pattern without a tag also matches every tag of that image.
        boolean patternHasTag = p.lastIndexOf(':') > p.lastIndexOf('/');
        String target =
                patternHasTag || p.endsWith("*")
                        ? imageName
                        : imageName.replaceFirst(":[^/:@]+$", "");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(target)
                .matches();
    }

    /**
     * The canonical form Docker itself resolves a reference to, so a rule cannot be dodged by
     * writing the same image differently: "nginx", "docker.io/nginx",
     * "index.docker.io/library/nginx" and "registry-1.docker.io/library/nginx:latest" all become
     * "docker.io/library/nginx:latest". "localhost/x" and "host:5000/x" are private registries, not
     * Docker Hub.
     */
    static String normalizeImageName(String imageName) {
        String ref = imageName.trim();
        String digest = "";
        int at = ref.indexOf('@');
        if (at >= 0) {
            digest = ref.substring(at);
            ref = ref.substring(0, at);
        }

        String registry = "docker.io";
        String path = ref;
        int firstSlash = ref.indexOf('/');
        if (firstSlash > 0) {
            String first = ref.substring(0, firstSlash);
            if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
                registry = first.toLowerCase(java.util.Locale.ROOT);
                path = ref.substring(firstSlash + 1);
            }
        }
        if (registry.equals("index.docker.io") || registry.equals("registry-1.docker.io")) {
            registry = "docker.io";
        }
        if (registry.equals("docker.io") && !path.contains("/")) {
            path = "library/" + path;
        }

        int colon = path.lastIndexOf(':');
        boolean hasTag = colon > path.lastIndexOf('/');
        if (!hasTag && digest.isEmpty()) {
            path = path + ":latest";
        }
        return registry + "/" + path + digest;
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
