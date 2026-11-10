package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StackRepository extends JpaRepository<Stack, String> {
    List<Stack> findByDockerHostIdOrderByNameAsc(String dockerHostId);

    Optional<Stack> findByDockerHostIdAndName(String dockerHostId, String name);

    Optional<Stack> findFirstByGitRepositoryId(String gitRepositoryId);
}
