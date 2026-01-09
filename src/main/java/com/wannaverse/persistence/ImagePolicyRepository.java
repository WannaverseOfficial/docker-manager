package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImagePolicyRepository extends JpaRepository<ImagePolicy, String> {

    Optional<ImagePolicy> findByName(String name);

    List<ImagePolicy> findByEnabledTrueOrderByPriorityAsc();

    List<ImagePolicy> findByPolicyTypeAndEnabledTrueOrderByPriorityAsc(
            ImagePolicy.PolicyType policyType);

    @Query("SELECT p FROM ImagePolicy p LEFT JOIN FETCH p.rules WHERE p.id = :id")
    Optional<ImagePolicy> findByIdWithRules(String id);

    @Query(
            "SELECT p FROM ImagePolicy p LEFT JOIN FETCH p.rules WHERE p.enabled = true ORDER BY"
                    + " p.priority ASC")
    List<ImagePolicy> findAllEnabledWithRules();

    @Query(
            "SELECT p FROM ImagePolicy p LEFT JOIN FETCH p.rules WHERE p.policyType = :policyType"
                    + " AND p.enabled = true ORDER BY p.priority ASC")
    List<ImagePolicy> findByTypeEnabledWithRules(ImagePolicy.PolicyType policyType);

    List<ImagePolicy> findAllByOrderByPriorityAsc();

    boolean existsByName(String name);
}
