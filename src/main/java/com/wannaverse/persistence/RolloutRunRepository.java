package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RolloutRunRepository extends JpaRepository<RolloutRun, String> {
    List<RolloutRun> findTop50ByRolloutIdOrderByCreatedAtDesc(String rolloutId);

    List<RolloutRun> findByRolloutIdAndStatusInOrderByCreatedAtAsc(
            String rolloutId, Collection<RolloutRun.Status> statuses);

    List<RolloutRun> findByStatusIn(Collection<RolloutRun.Status> statuses);

    @Query("select coalesce(max(r.number), 0) from RolloutRun r where r.rolloutId = ?1")
    int maxNumber(String rolloutId);

    List<RolloutRun> findByRolloutId(String rolloutId);
}
