package com.wannaverse.controllers;

import com.wannaverse.dto.ContainerHealthEventResponse;
import com.wannaverse.dto.ContainerHealthSummary;
import com.wannaverse.persistence.ContainerHealthEvent;
import com.wannaverse.persistence.ContainerHealthEventRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.RequirePermission;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/container-health")
public class ContainerHealthController {

    private final ContainerHealthEventRepository eventRepository;

    public ContainerHealthController(ContainerHealthEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping("/active")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list")
    public ResponseEntity<List<ContainerHealthEventResponse>> getActiveIssues() {
        List<ContainerHealthEvent> events =
                eventRepository.findByResolvedAtIsNullOrderByDetectedAtDesc();

        List<ContainerHealthEventResponse> responses =
                events.stream().map(ContainerHealthEventResponse::fromEntity).toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/active/count")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list")
    public ResponseEntity<Map<String, Long>> getActiveIssueCount() {
        long count = eventRepository.countByResolvedAtIsNull();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @GetMapping("/hosts/{hostId}/events")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<Map<String, Object>> getHostEvents(
            @PathVariable String hostId,
            @RequestParam(required = false) ContainerHealthEvent.EventType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<ContainerHealthEvent> events;

        if (type != null) {
            events =
                    eventRepository.findByDockerHostIdAndEventTypeOrderByDetectedAtDesc(
                            hostId, type, pageable);
        } else {
            events = eventRepository.findByDockerHostIdOrderByDetectedAtDesc(hostId, pageable);
        }

        List<ContainerHealthEventResponse> responses =
                events.getContent().stream().map(ContainerHealthEventResponse::fromEntity).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("content", responses);
        result.put("totalElements", events.getTotalElements());
        result.put("totalPages", events.getTotalPages());
        result.put("page", page);
        result.put("size", size);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/hosts/{hostId}/active")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<ContainerHealthEventResponse>> getHostActiveIssues(
            @PathVariable String hostId) {

        List<ContainerHealthEvent> events =
                eventRepository.findByDockerHostIdAndResolvedAtIsNullOrderByDetectedAtDesc(hostId);

        List<ContainerHealthEventResponse> responses =
                events.stream().map(ContainerHealthEventResponse::fromEntity).toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/events/{eventId}")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list")
    public ResponseEntity<ContainerHealthEventResponse> getEvent(@PathVariable String eventId) {
        return eventRepository
                .findById(eventId)
                .map(e -> ResponseEntity.ok(ContainerHealthEventResponse.fromEntity(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/events/{eventId}/resolve")
    @RequirePermission(resource = Resource.CONTAINERS, action = "stop")
    public ResponseEntity<ContainerHealthEventResponse> resolveEvent(@PathVariable String eventId) {
        return eventRepository
                .findById(eventId)
                .map(
                        event -> {
                            event.resolve();
                            eventRepository.save(event);
                            return ResponseEntity.ok(
                                    ContainerHealthEventResponse.fromEntity(event));
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/hosts/{hostId}/summary")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<ContainerHealthSummary> getHostSummary(@PathVariable String hostId) {
        long activeIssues = eventRepository.countByDockerHostIdAndResolvedAtIsNull(hostId);

        long now = System.currentTimeMillis();
        long oneDayAgo = now - (24 * 60 * 60 * 1000L);
        long sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000L);

        // Count events by type in last 24h
        Map<String, Long> eventsByType24h = new HashMap<>();
        List<Object[]> counts24h = eventRepository.countByEventTypeSince(hostId, oneDayAgo);
        long total24h = 0;
        for (Object[] row : counts24h) {
            ContainerHealthEvent.EventType type = (ContainerHealthEvent.EventType) row[0];
            Long count = (Long) row[1];
            eventsByType24h.put(type.name(), count);
            total24h += count;
        }

        // Count events by type in last 7d
        Map<String, Long> eventsByType7d = new HashMap<>();
        List<Object[]> counts7d = eventRepository.countByEventTypeSince(hostId, sevenDaysAgo);
        long total7d = 0;
        for (Object[] row : counts7d) {
            ContainerHealthEvent.EventType type = (ContainerHealthEvent.EventType) row[0];
            Long count = (Long) row[1];
            eventsByType7d.put(type.name(), count);
            total7d += count;
        }

        ContainerHealthSummary summary =
                ContainerHealthSummary.builder()
                        .activeIssues(activeIssues)
                        .totalEvents24h(total24h)
                        .totalEvents7d(total7d)
                        .eventsByType(eventsByType7d)
                        .eventsByType24h(eventsByType24h)
                        .build();

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/history")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "list",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<List<ContainerHealthEventResponse>> getContainerHistory(
            @PathVariable String hostId, @PathVariable String containerId) {

        List<ContainerHealthEvent> events =
                eventRepository.findByDockerHostIdAndContainerIdOrderByDetectedAtDesc(
                        hostId, containerId);

        List<ContainerHealthEventResponse> responses =
                events.stream().map(ContainerHealthEventResponse::fromEntity).toList();

        return ResponseEntity.ok(responses);
    }
}
