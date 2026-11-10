package com.wannaverse.service;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes old history that otherwise grows forever: resolved health events, finished git
 * deployments and pipeline runs (with their full logs), stack versions beyond the newest 50 per
 * stack, and old email and ingress logs. Container operations and snapshots are pruned separately
 * by {@link HistoryCleanupService}.
 *
 * <p>Each table is cleaned in its own short transaction so the single SQLite connection is never
 * held for long.
 */
@Service
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final long DAY = 24L * 60 * 60 * 1000;

    private final EntityManager em;
    private final TransactionTemplate tx;

    @Value("${app.history.retention-days:90}")
    private int retentionDays;

    public RetentionService(EntityManager em, TransactionTemplate tx) {
        this.em = em;
        this.tx = tx;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void prune() {
        long cutoff = System.currentTimeMillis() - retentionDays * DAY;
        long healthCutoff = System.currentTimeMillis() - 30 * DAY;

        run(
                "resolved health events",
                "DELETE FROM ContainerHealthEvent e WHERE e.resolvedAt IS NOT NULL AND e.detectedAt"
                        + " < :c",
                healthCutoff);
        run(
                "git deployments",
                "DELETE FROM DeploymentJob j WHERE j.createdAt < :c AND j.status IN :done",
                cutoff);
        run(
                "pipeline steps",
                "DELETE FROM StepExecution s WHERE s.stageExecution.id IN (SELECT st.id FROM"
                    + " StageExecution st WHERE st.execution.createdAt < :c AND st.execution.status"
                    + " IN :done)",
                cutoff);
        run(
                "pipeline stages",
                "DELETE FROM StageExecution st WHERE st.execution.id IN (SELECT e.id FROM"
                        + " PipelineExecution e WHERE e.createdAt < :c AND e.status IN :done)",
                cutoff);
        run(
                "pipeline runs",
                "DELETE FROM PipelineExecution e WHERE e.createdAt < :c AND e.status IN :done",
                cutoff);
        run("ingress log", "DELETE FROM IngressAuditLog l WHERE l.timestamp < :c", cutoff);
        pruneStackVersions();
    }

    private static final java.util.List<com.wannaverse.persistence.DeploymentJob.JobStatus>
            JOB_DONE =
                    java.util.List.of(
                            com.wannaverse.persistence.DeploymentJob.JobStatus.SUCCESS,
                            com.wannaverse.persistence.DeploymentJob.JobStatus.FAILED,
                            com.wannaverse.persistence.DeploymentJob.JobStatus.CANCELLED);
    private static final java.util.List<
                    com.wannaverse.persistence.PipelineExecution.ExecutionStatus>
            RUN_DONE =
                    java.util.List.of(
                            com.wannaverse.persistence.PipelineExecution.ExecutionStatus.SUCCESS,
                            com.wannaverse.persistence.PipelineExecution.ExecutionStatus.FAILED,
                            com.wannaverse.persistence.PipelineExecution.ExecutionStatus.CANCELLED);

    private void run(String what, String jpql, long cutoff) {
        try {
            Integer n =
                    tx.execute(
                            s -> {
                                var q = em.createQuery(jpql).setParameter("c", cutoff);
                                if (jpql.contains(":done")) {
                                    q.setParameter(
                                            "done",
                                            jpql.contains("DeploymentJob") ? JOB_DONE : RUN_DONE);
                                }
                                return q.executeUpdate();
                            });
            if (n != null && n > 0) log.info("Removed {} old {}", n, what);
        } catch (Exception e) {
            log.warn("Could not prune {}: {}", what, e.getMessage());
        }
    }

    /** Keeps the newest 50 versions of each stack (and always the active one). */
    private void pruneStackVersions() {
        try {
            tx.executeWithoutResult(
                    s -> {
                        var rows =
                                em.createQuery(
                                                "SELECT d.id, d.dockerHost.id, d.projectName,"
                                                    + " d.version, d.status FROM ComposeDeployment"
                                                    + " d ORDER BY d.version DESC",
                                                Object[].class)
                                        .getResultList();
                        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
                        java.util.List<String> remove = new java.util.ArrayList<>();
                        for (Object[] r : rows) {
                            String key = r[1] + "/" + r[2];
                            int n = seen.merge(key, 1, Integer::sum);
                            if (n > 50 && !"ACTIVE".equals(String.valueOf(r[4])))
                                remove.add((String) r[0]);
                        }
                        if (!remove.isEmpty()) {
                            em.createQuery(
                                            "UPDATE ComposeDeployment d SET d.previousDeployment ="
                                                    + " NULL WHERE d.previousDeployment.id IN :ids")
                                    .setParameter("ids", remove)
                                    .executeUpdate();
                            em.createQuery("DELETE FROM ComposeDeployment d WHERE d.id IN :ids")
                                    .setParameter("ids", remove)
                                    .executeUpdate();
                            log.info("Removed {} old stack versions", remove.size());
                        }
                    });
        } catch (Exception e) {
            log.warn("Could not prune stack versions: {}", e.getMessage());
        }
    }
}
