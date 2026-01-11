package com.wannaverse.controllers;

import com.wannaverse.persistence.ImagePolicy;
import com.wannaverse.persistence.ImagePolicyRule;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.ImagePolicyService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/image-policies")
public class ImagePolicyController {

    private final ImagePolicyService policyService;

    public ImagePolicyController(ImagePolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "list")
    public ResponseEntity<List<PolicyResponse>> listPolicies() {
        List<ImagePolicy> policies = policyService.getAllPolicies();
        List<PolicyResponse> responses = policies.stream().map(PolicyResponse::fromEntity).toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "read")
    public ResponseEntity<PolicyDetailResponse> getPolicy(@PathVariable String id) {
        return policyService
                .getPolicy(id)
                .map(p -> ResponseEntity.ok(PolicyDetailResponse.fromEntity(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "create")
    public ResponseEntity<PolicyResponse> createPolicy(@RequestBody CreatePolicyRequest request) {
        ImagePolicy policy =
                policyService.createPolicy(
                        request.name(),
                        request.description(),
                        request.policyType(),
                        request.priority() != null ? request.priority() : 100,
                        request.enabled() != null ? request.enabled() : true);

        return ResponseEntity.ok(PolicyResponse.fromEntity(policy));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "update")
    public ResponseEntity<PolicyResponse> updatePolicy(
            @PathVariable String id, @RequestBody UpdatePolicyRequest request) {

        ImagePolicy policy =
                policyService.updatePolicy(
                        id,
                        request.name(),
                        request.description(),
                        request.priority() != null ? request.priority() : 100,
                        request.enabled() != null ? request.enabled() : true);

        return ResponseEntity.ok(PolicyResponse.fromEntity(policy));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "delete")
    public ResponseEntity<Void> deletePolicy(@PathVariable String id) {
        policyService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rules")
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "update")
    public ResponseEntity<RuleResponse> addRule(
            @PathVariable String id, @RequestBody AddRuleRequest request) {

        ImagePolicyRule rule = policyService.addRule(id, request.pattern(), request.description());
        return ResponseEntity.ok(RuleResponse.fromEntity(rule));
    }

    @DeleteMapping("/{id}/rules/{ruleId}")
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "update")
    public ResponseEntity<Void> removeRule(@PathVariable String id, @PathVariable String ruleId) {
        policyService.removeRule(id, ruleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate")
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "read")
    public ResponseEntity<ValidationResponse> validateImage(@RequestBody ValidateRequest request) {
        ImagePolicyService.ValidationResult result = policyService.validateImage(request.image());
        return ResponseEntity.ok(
                new ValidationResponse(
                        request.image(), result.allowed(), result.reason(), result.policyName()));
    }

    @GetMapping("/check")
    @RequirePermission(resource = Resource.IMAGE_POLICIES, action = "read")
    public ResponseEntity<Map<String, Object>> checkImage(@RequestParam String image) {
        boolean allowed = policyService.isImageAllowed(image);
        return ResponseEntity.ok(Map.of("image", image, "allowed", allowed));
    }

    public record CreatePolicyRequest(
            String name,
            String description,
            ImagePolicy.PolicyType policyType,
            Integer priority,
            Boolean enabled) {}

    public record UpdatePolicyRequest(
            String name, String description, Integer priority, Boolean enabled) {}

    public record AddRuleRequest(String pattern, String description) {}

    public record ValidateRequest(String image) {}

    public record PolicyResponse(
            String id,
            String name,
            String description,
            String policyType,
            boolean enabled,
            int priority,
            int ruleCount,
            long createdAt,
            long updatedAt) {

        public static PolicyResponse fromEntity(ImagePolicy policy) {
            return new PolicyResponse(
                    policy.getId(),
                    policy.getName(),
                    policy.getDescription(),
                    policy.getPolicyType().name(),
                    policy.isEnabled(),
                    policy.getPriority(),
                    policy.getRules() != null ? policy.getRules().size() : 0,
                    policy.getCreatedAt(),
                    policy.getUpdatedAt());
        }
    }

    public record PolicyDetailResponse(
            String id,
            String name,
            String description,
            String policyType,
            boolean enabled,
            int priority,
            List<RuleResponse> rules,
            long createdAt,
            long updatedAt) {

        public static PolicyDetailResponse fromEntity(ImagePolicy policy) {
            List<RuleResponse> rules =
                    policy.getRules() != null
                            ? policy.getRules().stream().map(RuleResponse::fromEntity).toList()
                            : List.of();

            return new PolicyDetailResponse(
                    policy.getId(),
                    policy.getName(),
                    policy.getDescription(),
                    policy.getPolicyType().name(),
                    policy.isEnabled(),
                    policy.getPriority(),
                    rules,
                    policy.getCreatedAt(),
                    policy.getUpdatedAt());
        }
    }

    public record RuleResponse(String id, String pattern, String description, long createdAt) {

        public static RuleResponse fromEntity(ImagePolicyRule rule) {
            return new RuleResponse(
                    rule.getId(), rule.getPattern(), rule.getDescription(), rule.getCreatedAt());
        }
    }

    public record ValidationResponse(
            String image, boolean allowed, String reason, String policyName) {}
}
