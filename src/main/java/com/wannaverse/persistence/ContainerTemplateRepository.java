package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContainerTemplateRepository extends JpaRepository<ContainerTemplate, String> {

    List<ContainerTemplate> findBySystemTrue();

    List<ContainerTemplate> findBySystemFalse();

    List<ContainerTemplate> findByCategory(String category);

    List<ContainerTemplate> findByCategoryAndSystemTrue(String category);

    List<ContainerTemplate> findByType(ContainerTemplate.TemplateType type);

    List<ContainerTemplate> findByCreatedBy(String username);

    long countBySystemTrue();

    long countBySystemFalse();

    List<ContainerTemplate> findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
            String name, String description);

    List<ContainerTemplate> findAllByOrderByCategoryAscNameAsc();
}
