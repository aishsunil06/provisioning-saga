package dev.aishsunil.provisioning.inventory;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PoolExhaustedException.class)
    public ResponseEntity<Map<String, Object>> poolExhausted(PoolExhaustedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "POOL_EXHAUSTED", "lineType", ex.getLineType()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "VALIDATION_FAILED", "fields",
                    ex.getBindingResult().getFieldErrors().stream().map((error) -> error.getField()).sorted().toList()));
    }
}
