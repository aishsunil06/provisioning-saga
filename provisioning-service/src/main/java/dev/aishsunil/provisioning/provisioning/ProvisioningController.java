package dev.aishsunil.provisioning.provisioning;

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
@RequestMapping("/provisions")
public class ProvisioningController {

    private final ProvisioningService provisioning;

    public ProvisioningController(ProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @PostMapping
    public ResponseEntity<ProvisioningService.ProvisionView> activate(@RequestBody @Valid ProvisionRequest request) {
        ProvisioningService.ProvisionOutcome outcome = this.provisioning.activate(request.activationId(),
                request.msisdn());
        return ResponseEntity.status(outcome.changed() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.provision());
    }

    @GetMapping("/{activationId}")
    public ResponseEntity<ProvisioningService.ProvisionView> get(@PathVariable UUID activationId) {
        return this.provisioning.find(activationId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{activationId}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID activationId) {
        this.provisioning.deactivate(activationId);
        return ResponseEntity.noContent().build();
    }

    public record ProvisionRequest(@NotNull UUID activationId, @NotBlank String msisdn) {
    }

}
