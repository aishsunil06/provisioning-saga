package dev.aishsunil.provisioning.provisioning;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProvisioningFailedException.class)
    public ResponseEntity<Map<String, Object>> provisioningFailed(ProvisioningFailedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of("error", "PROVISIONING_FAILED", "msisdn", ex.getMsisdn()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "VALIDATION_FAILED", "fields",
                    ex.getBindingResult().getFieldErrors().stream().map((error) -> error.getField()).sorted().toList()));
    }

}
