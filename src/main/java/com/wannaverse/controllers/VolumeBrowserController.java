package com.wannaverse.controllers;

import com.wannaverse.persistence.Resource;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;
import com.wannaverse.service.VolumeBrowserService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Browse the files in a volume. Admin only: volumes hold every app's data, including its databases
 * and secrets. Reads use a read-only mount; changes use a separate writable one.
 */
@RestController
@AdminOnly
@RequestMapping("/api/docker/hosts/{hostId}/volumes/{volume}/files")
public class VolumeBrowserController {
    private final VolumeBrowserService browser;

    public VolumeBrowserController(VolumeBrowserService browser) {
        this.browser = browser;
    }

    @GetMapping
    public VolumeBrowserService.Listing list(
            @PathVariable String hostId,
            @PathVariable String volume,
            @RequestParam(defaultValue = "/") String path) {
        return browser.list(hostId, volume, path);
    }

    @GetMapping("/preview")
    public VolumeBrowserService.Preview preview(
            @PathVariable String hostId, @PathVariable String volume, @RequestParam String path) {
        return browser.preview(hostId, volume, path);
    }

    /** A file as itself, or a folder as a .tar archive. */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String hostId, @PathVariable String volume, @RequestParam String path) {
        var d = browser.describeDownload(hostId, volume, path);
        HttpHeaders headers = new HttpHeaders();
        // Plain names as they are; others also as filename* (RFC 6266) so every browser gets them.
        boolean ascii = d.filename().chars().allMatch(ch -> ch >= 32 && ch < 127 && ch != '"');
        headers.setContentDisposition(
                ascii
                        ? ContentDisposition.attachment().filename(d.filename()).build()
                        : ContentDisposition.attachment()
                                .filename(d.filename(), StandardCharsets.UTF_8)
                                .build());
        headers.setContentType(
                d.archive()
                        ? MediaType.parseMediaType("application/x-tar")
                        : MediaType.APPLICATION_OCTET_STREAM);
        if (d.size() >= 0) headers.setContentLength(d.size());
        StreamingResponseBody body =
                out -> browser.download(hostId, volume, path, d.archive(), out);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Auditable(resource = Resource.VOLUMES, action = "upload_files", resourceIdParam = "volume")
    public Map<String, Object> upload(
            @PathVariable String hostId,
            @PathVariable String volume,
            @RequestParam(defaultValue = "/") String path,
            @RequestParam("files") List<MultipartFile> files) {
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one file");
        }
        List<VolumeBrowserService.Upload> uploads = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
            // Browsers may send a path for folder uploads; keep only the file name.
            name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
            uploads.add(new VolumeBrowserService.Upload(name, f.getSize(), f::getInputStream));
        }
        browser.upload(hostId, volume, path, uploads);
        return Map.of("uploaded", uploads.size());
    }

    public record FolderRequest(String path, String name) {}

    @PostMapping("/folder")
    @Auditable(resource = Resource.VOLUMES, action = "create_folder", resourceIdParam = "volume")
    public ResponseEntity<Void> createFolder(
            @PathVariable String hostId,
            @PathVariable String volume,
            @RequestBody FolderRequest request) {
        browser.createFolder(hostId, volume, request.path(), request.name());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Auditable(resource = Resource.VOLUMES, action = "delete_files", resourceIdParam = "volume")
    public ResponseEntity<Void> delete(
            @PathVariable String hostId, @PathVariable String volume, @RequestParam String path) {
        browser.delete(hostId, volume, path);
        return ResponseEntity.noContent().build();
    }
}
