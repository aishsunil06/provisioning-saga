package dev.aishsunil.provisioning.inventory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of reserving a line.
 *
 * <p>It is a separate bean on purpose. When two callers race on the same
 * activation id, the loser hits the primary key and its transaction aborts,
 * taking the line it had already marked HELD back to FREE with it. Recovering
 * by re-reading the winner's row has to happen in a different transaction,
 * because the aborted one cannot run another statement, and a self-invocation
 * inside a single bean never crosses the proxy boundary that would start one.
 */
@Component
public class ReservationAllocator {

    private final LineRepository lines;

    private final ReservationRepository reservations;

    public ReservationAllocator(LineRepository lines, ReservationRepository reservations) {
        this.lines = lines;
        this.reservations = reservations;
    }

    @Transactional
    public Allocation allocate(UUID activationId, String lineType) {
        Optional<Reservation> existing = this.reservations.findById(activationId);
        if (existing.isPresent()) {
            return new Allocation(existing.get(), false);
        }
        Line line = this.lines.claimFreeLine(lineType).orElseThrow(() -> new PoolExhaustedException(lineType));
        line.setState(Line.HELD);
        this.lines.save(line);
        // Flushed inside this transaction so a duplicate activation id fails
        // here, where the rollback also returns the line above to the pool.
        return new Allocation(this.reservations.saveAndFlush(new Reservation(activationId, line.getId())), true);
    }

    @Transactional
    public void release(UUID activationId) {
        this.reservations.findById(activationId).ifPresent((reservation) -> {
            if (Reservation.HELD.equals(reservation.getState())) {
                reservation.release();
                this.lines.findById(reservation.getLineId()).ifPresent((line) -> line.setState(Line.FREE));
            }
        });
    }

    /** A reservation, and whether this call is what inserted it. */
    public record Allocation(Reservation reservation, boolean created) {
    }
}
