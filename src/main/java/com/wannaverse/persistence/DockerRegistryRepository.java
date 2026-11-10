package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DockerRegistryRepository extends JpaRepository<DockerRegistry, String> {

    Optional<DockerRegistry> findByName(String name);

    Optional<DockerRegistry> findByUrl(String url);

    List<DockerRegistry> findByEnabledTrue();

    Optional<DockerRegistry> findByIsDefaultTrue();

    List<DockerRegistry> findAllByOrderByNameAsc();

    boolean existsByName(String name);

    boolean existsByUrl(String url);

    @Modifying
    @Query("UPDATE DockerRegistry r SET r.isDefault = false WHERE r.isDefault = true")
    void clearDefaultRegistry();
}
