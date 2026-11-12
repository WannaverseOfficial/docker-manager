package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.ImagePolicy;
import com.wannaverse.persistence.ImagePolicyRepository;
import com.wannaverse.persistence.ImagePolicyRule;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.service.ImagePolicyService;

import lombok.Data;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** {@code imagePolicies}: rules for which images may be pulled and run. */
@Component
public class ImagePoliciesHandler implements ResourceHandler<ImagePoliciesHandler.PolicySpec> {

    @Data
    @Doc("Allows or denies images whose names match its rules")
    public static class PolicySpec {
        @Doc("The policy's name. Defaults to the entry's key.")
        private String name;

        @Doc(value = "What the policy says about matching images: allow or deny", required = true)
        private ImagePolicy.PolicyType type;

        @Doc("What the policy is for")
        private String description;

        @Doc("Lower numbers are checked first (default 100)")
        private Integer priority;

        @Doc("Whether the policy is checked (default true)")
        private Boolean enabled;

        @Doc("Image name patterns, such as docker.io/library/* or ghcr.io/acme/*")
        private List<RuleSpec> rules = new ArrayList<>();
    }

    @Data
    public static class RuleSpec {
        @Doc(value = "Image name pattern; * matches anything", required = true)
        private String pattern;

        @Doc("What the rule is for")
        private String description;
    }

    private final ImagePolicyRepository policies;
    private final ImagePolicyService service;

    public ImagePoliciesHandler(ImagePolicyRepository policies, ImagePolicyService service) {
        this.policies = policies;
        this.service = service;
    }

    @Override
    public String section() {
        return "imagePolicies";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public Class<PolicySpec> specType() {
        return PolicySpec.class;
    }

    @Override
    public String description() {
        return "Image rules: which images may be pulled and run";
    }

    @Override
    public void validate(Entry<PolicySpec> entry, ApplyContext ctx) {
        for (RuleSpec r : entry.spec().getRules()) {
            if (r == null || r.getPattern() == null || r.getPattern().isBlank()) {
                throw new IacException("rules: every rule needs a pattern");
            }
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<PolicySpec> entry, ApplyContext ctx) {
        return policies.findByName(entry.nameOr(entry.spec().getName())).map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        return policies.findByName(name).map(ImagePolicy::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return policies.existsById(record.getTargetId());
    }

    @Override
    @Transactional
    public Target create(Entry<PolicySpec> entry, ApplyContext ctx) {
        PolicySpec s = entry.spec();
        try {
            ImagePolicy p =
                    service.createPolicy(
                            entry.nameOr(s.getName()),
                            s.getDescription(),
                            s.getType(),
                            s.getPriority() == null ? 100 : s.getPriority(),
                            s.getEnabled() == null || s.getEnabled());
            syncRules(p.getId(), s);
            return target(p);
        } catch (IllegalArgumentException e) {
            throw new IacException(e.getMessage());
        }
    }

    @Override
    @Transactional
    public Target update(
            Entry<PolicySpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        PolicySpec s = entry.spec();
        try {
            ImagePolicy p =
                    service.updatePolicy(
                            record.getTargetId(),
                            entry.nameOr(s.getName()),
                            s.getDescription(),
                            s.getPriority() == null ? 100 : s.getPriority(),
                            s.getEnabled() == null || s.getEnabled());
            if (p.getPolicyType() != s.getType()) {
                p.setPolicyType(s.getType());
                p = policies.save(p);
            }
            if (changes.has("rules")) syncRules(p.getId(), s);
            return target(p);
        } catch (IllegalArgumentException e) {
            throw new IacException(e.getMessage());
        }
    }

    private void syncRules(String policyId, PolicySpec s) {
        List<RuleSpec> wanted = new ArrayList<>(s.getRules());
        ImagePolicy p = service.getPolicy(policyId).orElseThrow();
        for (ImagePolicyRule have : new ArrayList<>(p.getRules())) {
            RuleSpec match =
                    wanted.stream()
                            .filter(
                                    w ->
                                            w.getPattern().trim().equals(have.getPattern())
                                                    && Objects.equals(
                                                            w.getDescription(),
                                                            have.getDescription()))
                            .findFirst()
                            .orElse(null);
            if (match != null) wanted.remove(match);
            else service.removeRule(policyId, have.getId());
        }
        for (RuleSpec w : wanted) {
            service.addRule(policyId, w.getPattern().trim(), w.getDescription());
        }
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (policies.existsById(record.getTargetId())) service.deletePolicy(record.getTargetId());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, PolicySpec> export(ExportContext ctx) {
        Map<String, PolicySpec> out = new LinkedHashMap<>();
        for (ImagePolicy summary : service.getAllPolicies()) {
            ImagePolicy p = service.getPolicy(summary.getId()).orElse(summary);
            String key = ctx.key(section(), p.getId(), p.getName());
            PolicySpec s = new PolicySpec();
            if (!key.equals(p.getName())) s.setName(p.getName());
            s.setType(p.getPolicyType());
            s.setDescription(p.getDescription());
            if (p.getPriority() != 100) s.setPriority(p.getPriority());
            if (!p.isEnabled()) s.setEnabled(false);
            for (ImagePolicyRule r : p.getRules()) {
                RuleSpec rs = new RuleSpec();
                rs.setPattern(r.getPattern());
                rs.setDescription(r.getDescription());
                s.getRules().add(rs);
            }
            out.put(key, s);
        }
        return out;
    }

    private Target target(ImagePolicy p) {
        return new Target(p.getId(), null, p.getName());
    }
}
