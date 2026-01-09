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

    @Query("SELECT r FROM DockerRegistry r WHERE r.url LIKE %:urlPart% AND r.enabled = true")
    List<DockerRegistry> findByUrlContaining(String urlPart);

    @Modifying
    @Query("UPDATE DockerRegistry r SET r.isDefault = false WHERE r.isDefault = true")
    void clearDefaultRegistry();
}
