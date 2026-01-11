package com.wannaverse.service;

import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HistoryCleanupService {
    private static final Logger log = LoggerFactory.getLogger(HistoryCleanupService.class);

    private final DockerOperationRepository operationRepository;
    private final StateSnapshotRepository snapshotRepository;

    @Value("${app.history.retention-days:90}")
    private int retentionDays;

    public HistoryCleanupService(
            DockerOperationRepository operationRepository,
            StateSnapshotRepository snapshotRepository) {
        this.operationRepository = operationRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldHistory() {
        log.info("Starting history cleanup (retention: {} days)", retentionDays);

        long cutoffTimestamp =
                System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

        try {
            List<DockerOperation> oldOperations =
                    operationRepository.findOlderThan(cutoffTimestamp);

            if (oldOperations.isEmpty()) {
                log.info("No old operations to clean up");
                return;
            }

            log.info("Found {} operations older than {} days", oldOperations.size(), retentionDays);

            int deletedSnapshots = 0;
            int deletedOperations = 0;

            for (DockerOperation operation : oldOperations) {
                List<StateSnapshot> snapshots =
                        snapshotRepository.findByOperationIdOrderByCreatedAtAsc(operation.getId());

                if (!snapshots.isEmpty()) {
                    snapshotRepository.deleteAll(snapshots);
                    deletedSnapshots += snapshots.size();
                }

                operationRepository.delete(operation);
                deletedOperations++;
            }

            log.info(
                    "Cleanup complete: deleted {} operations and {} snapshots",
                    deletedOperations,
                    deletedSnapshots);

        } catch (Exception e) {
            log.error("History cleanup failed", e);
        }
    }

    @Transactional
    public CleanupResult manualCleanup(int days) {
        log.info("Manual cleanup requested (retention: {} days)", days);

        long cutoffTimestamp = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);

        List<DockerOperation> oldOperations = operationRepository.findOlderThan(cutoffTimestamp);

        if (oldOperations.isEmpty()) {
            return new CleanupResult(0, 0);
        }

        int deletedSnapshots = 0;
        int deletedOperations = 0;

        for (DockerOperation operation : oldOperations) {
            List<StateSnapshot> snapshots =
                    snapshotRepository.findByOperationIdOrderByCreatedAtAsc(operation.getId());

            if (!snapshots.isEmpty()) {
                snapshotRepository.deleteAll(snapshots);
                deletedSnapshots += snapshots.size();
            }

            operationRepository.delete(operation);
            deletedOperations++;
        }

        log.info(
                "Manual cleanup complete: deleted {} operations and {} snapshots",
                deletedOperations,
                deletedSnapshots);

        return new CleanupResult(deletedOperations, deletedSnapshots);
    }

    public record CleanupResult(int deletedOperations, int deletedSnapshots) {}
}
