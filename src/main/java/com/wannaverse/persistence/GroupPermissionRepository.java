package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupPermissionRepository extends JpaRepository<GroupPermission, String> {
    List<GroupPermission> findByGroupId(String groupId);

    @Query(
            "SELECT gp FROM GroupPermission gp "
                    + "JOIN gp.group g "
                    + "JOIN g.members u "
                    + "WHERE u.id = :userId "
                    + "AND gp.resource = :resource "
                    + "AND (gp.action = :action OR gp.action = '*') "
                    + "AND (gp.scopeHostId IS NULL OR gp.scopeHostId = :hostId)")
    List<GroupPermission> findMatchingPermissionsForUser(
            @Param("userId") String userId,
            @Param("resource") Resource resource,
            @Param("action") String action,
            @Param("hostId") String hostId);

    @Query(
            "SELECT p FROM GroupPermission p WHERE p.group.id = :groupId AND p.resource = :resource"
                + " AND p.action = :action AND (p.scopeHostId = :hostId OR (p.scopeHostId IS NULL"
                + " AND :hostId IS NULL)) AND (p.scopeResourceId = :resourceId OR"
                + " (p.scopeResourceId IS NULL AND :resourceId IS NULL))")
    Optional<GroupPermission> findExactPermission(
            @Param("groupId") String groupId,
            @Param("resource") Resource resource,
            @Param("action") String action,
            @Param("hostId") String hostId,
            @Param("resourceId") String resourceId);

    Optional<GroupPermission> findByGroupIdAndResourceAndActionAndScopeHostId(
            String groupId, Resource resource, String action, String scopeHostId);

    void deleteByGroupId(String groupId);
}
