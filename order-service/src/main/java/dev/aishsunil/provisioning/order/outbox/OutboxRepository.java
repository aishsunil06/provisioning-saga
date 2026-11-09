package dev.aishsunil.provisioning.order.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxRecord, UUID> {

    /**
     * Claims a batch of undelivered intents.
     *
     * <p>SKIP LOCKED is what lets two relays run at once: each claims rows the
     * other is not holding, so neither waits and neither takes the same row.
     * Ordering by sequence_no descending preserves reverse-step order, so a line
     * is deprovisioned before it is released back to the pool.
     */
    @Query(value = "SELECT * FROM outbox WHERE delivered_at IS NULL ORDER BY sequence_no DESC, created_at LIMIT :batchSize FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxRecord> claimUndelivered(@Param("batchSize") int batchSize);

    List<OutboxRecord> findByActivationId(UUID activationId);

}
