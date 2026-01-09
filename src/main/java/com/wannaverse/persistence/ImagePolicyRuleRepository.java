package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImagePolicyRuleRepository extends JpaRepository<ImagePolicyRule, String> {

    List<ImagePolicyRule> findByPolicyId(String policyId);

    void deleteByPolicyId(String policyId);
}
