package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserPermissionRepository extends JpaRepository<UserPermission, String> {
    List<UserPermission> findByUserId(String userId);

    @Query(
            "SELECT p FROM UserPermission p WHERE p.user.id = :userId "
                    + "AND p.resource = :resource "
                    + "AND (p.action = :action OR p.action = '*') "
                    + "AND (p.scopeHostId IS NULL OR p.scopeHostId = :hostId)")
    List<UserPermission> findMatchingPermissions(
            @Param("userId") String userId,
            @Param("resource") Resource resource,
            @Param("action") String action,
            @Param("hostId") String hostId);

    @Query(
            "SELECT p FROM UserPermission p WHERE p.user.id = :userId AND p.resource = :resource"
                + " AND p.action = :action AND (p.scopeHostId = :hostId OR (p.scopeHostId IS NULL"
                + " AND :hostId IS NULL)) AND (p.scopeResourceId = :resourceId OR"
                + " (p.scopeResourceId IS NULL AND :resourceId IS NULL))")
    Optional<UserPermission> findExactPermission(
            @Param("userId") String userId,
            @Param("resource") Resource resource,
            @Param("action") String action,
            @Param("hostId") String hostId,
            @Param("resourceId") String resourceId);

    Optional<UserPermission> findByUserIdAndResourceAndActionAndScopeHostId(
            String userId, Resource resource, String action, String scopeHostId);

    void deleteByUserId(String userId);
}
