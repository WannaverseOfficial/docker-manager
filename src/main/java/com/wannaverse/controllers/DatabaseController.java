package com.wannaverse.controllers;

import com.wannaverse.iac.ConfigLocked;
import com.wannaverse.persistence.DatabaseBackup;
import com.wannaverse.persistence.DatabaseServer;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.DatabaseBackupService;
import com.wannaverse.service.PostgresService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL servers: databases, users, the SQL console, containers that use them, backups. */
@RestController
@AdminOnly
@RequestMapping("/api/databases")
public class DatabaseController {
    private final PostgresService postgres;
    private final DatabaseBackupService backups;

    public DatabaseController(PostgresService postgres, DatabaseBackupService backups) {
        this.postgres = postgres;
        this.backups = backups;
    }

    private static String user() {
        return SecurityContextHolder.getCurrentUsername();
    }

    // --- servers -----------------------------------------------------------------------------

    @GetMapping("/versions")
    public List<String> versions() {
        return PostgresService.VERSIONS;
    }

    @GetMapping("/hosts/{hostId}/servers")
    public List<PostgresService.ServerView> list(@PathVariable String hostId) {
        return postgres.list(hostId);
    }

    @PostMapping("/hosts/{hostId}/servers")
    @Auditable(
            resource = Resource.DATABASES,
            action = "create_database_server",
            captureRequestBody = true)
    public DatabaseServer create(
            @PathVariable String hostId, @RequestBody PostgresService.CreateRequest request) {
        DatabaseServer s = postgres.create(hostId, request);
        backups.adopt(s);
        return s;
    }

    @GetMapping("/servers/{id}")
    public PostgresService.ServerView get(@PathVariable String id) {
        return postgres.view(id);
    }

    @DeleteMapping("/servers/{id}")
    @ConfigLocked(section = "databases", id = "id")
    @Auditable(
            resource = Resource.DATABASES,
            action = "remove_database_server",
            resourceIdParam = "id")
    public ResponseEntity<Void> remove(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean deleteData,
            @RequestParam(defaultValue = "false") boolean deleteBackups) {
        if (deleteBackups) backups.deleteAll(id);
        postgres.remove(id, deleteData);
        return ResponseEntity.noContent().build();
    }

    public record PortRequest(Integer port, String address) {}

    /** Opens, moves or closes the port on the host (recreates the container; data is kept). */
    @PutMapping("/servers/{id}/port")
    @ConfigLocked(section = "databases", id = "id")
    @Auditable(
            resource = Resource.DATABASES,
            action = "change_database_port",
            captureRequestBody = true)
    public DatabaseServer changePort(@PathVariable String id, @RequestBody PortRequest request) {
        return backups.whileIdle(
                id,
                "Changing the port",
                () -> postgres.changePort(id, request.port(), request.address()));
    }

    @DeleteMapping("/servers/{id}/result")
    public ResponseEntity<Void> clearResult(@PathVariable String id) {
        postgres.clearResult(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/servers/{id}/password")
    @Auditable(
            resource = Resource.DATABASES,
            action = "reveal_database_password",
            resourceIdParam = "id")
    public Map<String, String> password(@PathVariable String id) {
        return Map.of("password", postgres.superuserPassword(id));
    }

    // --- SQL ---------------------------------------------------------------------------------

    public record QueryRequest(String database, String sql, boolean readOnly) {}

    @PostMapping("/servers/{id}/query")
    @Auditable(resource = Resource.DATABASES, action = "run_query", resourceIdParam = "id")
    public PostgresService.QueryResult query(
            @PathVariable String id, @RequestBody QueryRequest request) {
        return postgres.query(id, request.database(), request.sql(), request.readOnly());
    }

    // --- databases and users -----------------------------------------------------------------

    @GetMapping("/servers/{id}/databases")
    public List<PostgresService.DatabaseInfo> databases(@PathVariable String id) {
        return postgres.databases(id);
    }

    public record CreateDatabaseRequest(String name, String owner, boolean newUser) {}

    @PostMapping("/servers/{id}/databases")
    @Auditable(resource = Resource.DATABASES, action = "create_database", captureRequestBody = true)
    public PostgresService.Created createDatabase(
            @PathVariable String id, @RequestBody CreateDatabaseRequest request) {
        return postgres.createDatabase(id, request.name(), request.owner(), request.newUser());
    }

    @DeleteMapping("/servers/{id}/databases/{name}")
    @Auditable(resource = Resource.DATABASES, action = "drop_database", resourceIdParam = "name")
    public ResponseEntity<Void> dropDatabase(@PathVariable String id, @PathVariable String name) {
        postgres.dropDatabase(id, name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/servers/{id}/roles")
    public List<PostgresService.Role> roles(@PathVariable String id) {
        return postgres.roles(id);
    }

    public record RoleRequest(String name, String password) {}

    @PostMapping("/servers/{id}/roles")
    @Auditable(resource = Resource.DATABASES, action = "create_database_user")
    public Map<String, String> createRole(
            @PathVariable String id, @RequestBody RoleRequest request) {
        String pw = postgres.createRole(id, request.name(), request.password());
        return Map.of("name", request.name(), "password", pw);
    }

    @PutMapping("/servers/{id}/roles/{name}/password")
    @Auditable(
            resource = Resource.DATABASES,
            action = "set_database_password",
            resourceIdParam = "name")
    public Map<String, String> setPassword(
            @PathVariable String id, @PathVariable String name, @RequestBody RoleRequest request) {
        return Map.of("name", name, "password", postgres.setPassword(id, name, request.password()));
    }

    @DeleteMapping("/servers/{id}/roles/{name}")
    @Auditable(
            resource = Resource.DATABASES,
            action = "drop_database_user",
            resourceIdParam = "name")
    public ResponseEntity<Void> dropRole(@PathVariable String id, @PathVariable String name) {
        postgres.dropRole(id, name);
        return ResponseEntity.noContent().build();
    }

    public record AccessRequest(String database, String role, String level) {}

    @PutMapping("/servers/{id}/access")
    @ConfigLocked(section = "databases", id = "id")
    @Auditable(
            resource = Resource.DATABASES,
            action = "set_database_access",
            captureRequestBody = true)
    public ResponseEntity<Void> access(
            @PathVariable String id, @RequestBody AccessRequest request) {
        postgres.setAccess(id, request.database(), request.role(), request.level());
        return ResponseEntity.noContent().build();
    }

    // --- containers --------------------------------------------------------------------------

    @GetMapping("/servers/{id}/members")
    public List<PostgresService.Member> members(@PathVariable String id) {
        return postgres.members(id);
    }

    public record MemberRequest(String container) {}

    @PostMapping("/servers/{id}/members")
    @Auditable(
            resource = Resource.DATABASES,
            action = "connect_container",
            captureRequestBody = true)
    public ResponseEntity<Void> connect(
            @PathVariable String id, @RequestBody MemberRequest request) {
        postgres.connect(id, request.container());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/servers/{id}/members/{container}")
    @Auditable(
            resource = Resource.DATABASES,
            action = "disconnect_container",
            resourceIdParam = "container")
    public ResponseEntity<Void> disconnect(
            @PathVariable String id, @PathVariable String container) {
        postgres.disconnect(id, container);
        return ResponseEntity.noContent().build();
    }

    // --- backups -----------------------------------------------------------------------------

    @GetMapping("/servers/{id}/backups")
    public List<DatabaseBackup> backups(@PathVariable String id) {
        return backups.list(id);
    }

    public record BackupRequest(String database) {}

    @PostMapping("/servers/{id}/backups")
    @Auditable(resource = Resource.DATABASES, action = "backup_database", captureRequestBody = true)
    public ResponseEntity<DatabaseBackup> backup(
            @PathVariable String id, @RequestBody(required = false) BackupRequest request) {
        String db =
                request == null || request.database() == null || request.database().isBlank()
                        ? null
                        : request.database();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(backups.startBackup(id, db, "MANUAL", user()));
    }

    /** The file is the raw request body (no multipart), so large dumps stream straight to disk. */
    @PostMapping(value = "/servers/{id}/backups/upload", consumes = MediaType.ALL_VALUE)
    @Auditable(resource = Resource.DATABASES, action = "upload_backup", resourceIdParam = "id")
    public DatabaseBackup upload(
            @PathVariable String id,
            @RequestParam(required = false) String name,
            HttpServletRequest request)
            throws IOException {
        return backups.upload(id, name, request.getInputStream(), user());
    }

    @GetMapping("/backups/{backupId}/download")
    @Auditable(
            resource = Resource.DATABASES,
            action = "download_backup",
            resourceIdParam = "backupId")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String backupId)
            throws IOException {
        DatabaseBackupService.Download d = backups.download(backupId);
        long size = Files.size(d.file());
        StreamingResponseBody body = out -> Files.copy(d.file(), out);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.name()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .body(body);
    }

    @DeleteMapping("/backups/{backupId}")
    @Auditable(
            resource = Resource.DATABASES,
            action = "delete_backup",
            resourceIdParam = "backupId")
    public ResponseEntity<Void> deleteBackup(@PathVariable String backupId) {
        backups.delete(backupId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/backups/{backupId}/restore")
    @Auditable(resource = Resource.DATABASES, action = "restore_backup", captureRequestBody = true)
    public ResponseEntity<DatabaseBackupService.RestoreStarted> restore(
            @PathVariable String backupId,
            @RequestBody(required = false) DatabaseBackupService.RestoreRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(backups.restore(backupId, request, user()));
    }

    @PostMapping("/backups/{backupId}/s3")
    @Auditable(
            resource = Resource.DATABASES,
            action = "copy_backup_to_s3",
            resourceIdParam = "backupId")
    public DatabaseBackup retryS3(@PathVariable String backupId) {
        return backups.retryS3(backupId);
    }

    @PutMapping("/servers/{id}/settings")
    @ConfigLocked(section = "databases", id = "id")
    @Auditable(
            resource = Resource.DATABASES,
            action = "update_backup_settings",
            resourceIdParam = "id")
    public DatabaseServer settings(
            @PathVariable String id, @RequestBody DatabaseBackupService.Settings request) {
        return backups.updateSettings(id, request);
    }

    @PostMapping("/servers/{id}/settings/s3-test")
    public Map<String, Boolean> testS3(@PathVariable String id) {
        backups.testS3(id);
        return Map.of("ok", true);
    }

    // --- importing from other containers -----------------------------------------------------

    @GetMapping("/hosts/{hostId}/sources")
    public List<DatabaseBackupService.Source> sources(@PathVariable String hostId) {
        return backups.sources(hostId);
    }

    public record SourceRequest(String container, String user, String password) {}

    @PostMapping("/hosts/{hostId}/sources/databases")
    public Map<String, Object> sourceDatabases(
            @PathVariable String hostId, @RequestBody SourceRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put(
                "databases",
                backups.sourceDatabases(
                        hostId, request.container(), request.user(), request.password()));
        return body;
    }

    @PostMapping("/servers/{id}/import")
    @Auditable(resource = Resource.DATABASES, action = "import_database", resourceIdParam = "id")
    public ResponseEntity<DatabaseBackupService.RestoreStarted> importDatabase(
            @PathVariable String id, @RequestBody DatabaseBackupService.ImportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(backups.importDatabase(id, request, user()));
    }
}
