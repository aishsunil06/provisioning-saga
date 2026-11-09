package dev.aishsunil.provisioning.order;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/activations")
public class ActivationController {

    private final ActivationService activations;

    public ActivationController(ActivationService activations) {
        this.activations = activations;
    }

    /**
     * Starts an activation and reports where it ended up.
     *
     * <p>202 rather than 201: the work the caller cares about is the activation,
     * not the order row, and the state in the body may already be terminal. The
     * bench polls the GET regardless, so one contract serves both.
     */
    @PostMapping
    public ResponseEntity<ActivationService.ActivationView> activate(@RequestBody @Valid ActivationRequest request) {
        ActivationService.ActivationView view = this.activations.activate(request.customerId(), request.plan(),
                request.lineType());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping("/{activationId}")
    public ResponseEntity<ActivationService.ActivationView> get(@PathVariable UUID activationId) {
        return this.activations.find(activationId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record ActivationRequest(@NotBlank String customerId, @NotBlank String plan, @NotBlank String lineType) {
    }

}
