package dev.aishsunil.provisioning.inventory;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LineRepository extends JpaRepository<Line, Long> {

    /**
     * Claims one free line of the given type, locking only that row.
     *
     * <p>SKIP LOCKED is what keeps concurrent reservations from serializing: a
     * caller that finds the first candidate locked moves on to the next free
     * line instead of queueing behind it. Locking the pool instead of a row
     * would turn every reservation into a queue, which is the cost this avoids.
     */
    @Query(value = "SELECT * FROM lines WHERE line_type = :lineType AND state = 'FREE' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Line> claimFreeLine(@Param("lineType") String lineType);

    /**
     * Counts free lines without locking any of them.
     *
     * <p>This is how an empty {@link #claimFreeLine} result gets interpreted.
     * SKIP LOCKED skips rows another transaction holds, so a caller arriving
     * while every free line happens to be locked gets back nothing at all and
     * cannot tell that from a pool that is genuinely empty. Reporting
     * exhaustion to a customer because three other activations were mid-flight
     * would be wrong, so the two cases are separated here.
     */
    long countByLineTypeAndState(String lineType, String state);

}
