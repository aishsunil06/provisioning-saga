package dev.aishsunil.provisioning.inventory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reserving and releasing lines, idempotent by activation id. */
@Service
public class InventoryService {

    /**
     * How many times a caller that lost the primary-key race will look for the
     * winner's row before giving up.
     *
     * <p>Losing the race and finding nothing is not a contradiction: the winner
     * holds its row in an uncommitted transaction for a short window, during
     * which the loser's own insert has already failed but there is still
     * nothing to read. One re-read is therefore not enough, and waiting
     * forever would hide a genuine failure, so the number is small and bounded.
     */
    private static final int MAX_ATTEMPTS = 6;

    private final ReservationAllocator allocator;

    private final ReservationRepository reservations;

    private final LineRepository lines;

    public InventoryService(ReservationAllocator allocator, ReservationRepository reservations, LineRepository lines) {
        this.allocator = allocator;
        this.reservations = reservations;
        this.lines = lines;
    }

    /**
     * Reserves a line for an activation, or returns the one it already holds.
     * @return the reservation, and whether this call is what created it
     */
    public ReserveOutcome reserve(UUID activationId, String lineType) {
        Optional<ReservationView> existing = find(activationId);
        if (existing.isPresent()) {
            return new ReserveOutcome(existing.get(), false);
        }
        RuntimeException lost = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                ReservationAllocator.Allocation allocation = this.allocator.allocate(activationId, lineType);
                return new ReserveOutcome(toView(allocation.reservation()), allocation.created());
            }
            catch (DataIntegrityViolationException ex) {
                lost = ex;
                Optional<ReservationView> winner = find(activationId);
                if (winner.isPresent()) {
                    return new ReserveOutcome(winner.get(), false);
                }
                pauseBeforeRetry(attempt);
            }
            catch (PoolExhaustedException ex) {
                lost = ex;
                // Every free line was locked by another in-flight reservation,
                // not actually taken. Retry while the pool still has free lines;
                // only a pool with none left is genuinely exhausted.
                Optional<ReservationView> winner = find(activationId);
                if (winner.isPresent()) {
                    return new ReserveOutcome(winner.get(), false);
                }
                if (!hasFreeLine(lineType)) {
                    throw ex;
                }
                pauseBeforeRetry(attempt);
            }
        }
        throw lost;
    }

    @Transactional(readOnly = true)
    public boolean hasFreeLine(String lineType) {
        return this.lines.countByLineTypeAndState(lineType, Line.FREE) > 0;
    }

    public void release(UUID activationId) {
        this.allocator.release(activationId);
    }

    @Transactional(readOnly = true)
    public Optional<ReservationView> find(UUID activationId) {
        return this.reservations.findById(activationId).map(this::toView);
    }

    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(1L << attempt, 32L));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a concurrent reservation to commit", ex);
        }
    }

    private ReservationView toView(Reservation reservation) {
        String msisdn = this.lines.findById(reservation.getLineId())
            .map(Line::getMsisdn)
            .orElseThrow(() -> new IllegalStateException(
                    "reservation %s points at line %d, which does not exist".formatted(reservation.getActivationId(),
                            reservation.getLineId())));
        return new ReservationView(reservation.getActivationId(), msisdn, reservation.getState());
    }

    /** What a caller of this service is told about a reservation. */
    public record ReservationView(UUID activationId, String msisdn, String state) {
    }

    public record ReserveOutcome(ReservationView reservation, boolean created) {
    }
}
