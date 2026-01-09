package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserGroupRepository extends JpaRepository<UserGroup, String> {
    Optional<UserGroup> findByName(String name);

    boolean existsByName(String name);
}
