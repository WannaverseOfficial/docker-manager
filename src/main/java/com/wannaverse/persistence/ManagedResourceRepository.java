package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManagedResourceRepository extends JpaRepository<ManagedResource, String> {
    List<ManagedResource> findBySection(String section);

    Optional<ManagedResource> findBySectionAndEntryKey(String section, String entryKey);

    List<ManagedResource> findBySectionAndTargetId(String section, String targetId);

    boolean existsBySectionAndTargetId(String section, String targetId);

    boolean existsBySectionAndTargetIdAndHostId(String section, String targetId, String hostId);
}
