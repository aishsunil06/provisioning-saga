package dev.aishsunil.provisioning.order;

import java.util.Map;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "VALIDATION_FAILED", "fields",
                    ex.getBindingResult().getFieldErrors().stream().map((error) -> error.getField()).sorted().toList()));
    }

    /**
     * A body that is not JSON at all.
     *
     * <p>Without this the client gets a 500 carrying a Jackson parse message,
     * which reads as a bug in the service rather than in the request.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "MALFORMED_REQUEST"));
    }

}
