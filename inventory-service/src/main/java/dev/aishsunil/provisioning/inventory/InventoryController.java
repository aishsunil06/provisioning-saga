package dev.aishsunil.provisioning.inventory;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reservations")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    /**
     * Reserves a line.
     *
     * <p>201 when this call took the line out of the pool, 200 when the
     * activation already held one. A replay created nothing, so reporting
     * Created for it would misdescribe what happened.
     */
    @PostMapping
    public ResponseEntity<InventoryService.ReservationView> reserve(@RequestBody @Valid ReserveRequest request) {
        InventoryService.ReserveOutcome outcome = this.inventory.reserve(request.activationId(), request.lineType());
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.reservation());
    }

    @GetMapping("/{activationId}")
    public ResponseEntity<InventoryService.ReservationView> get(@PathVariable UUID activationId) {
        return this.inventory.find(activationId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{activationId}")
    public ResponseEntity<Void> release(@PathVariable UUID activationId) {
        this.inventory.release(activationId);
        return ResponseEntity.noContent().build();
    }

    public record ReserveRequest(@NotNull UUID activationId, @NotBlank String lineType) {
    }
}
