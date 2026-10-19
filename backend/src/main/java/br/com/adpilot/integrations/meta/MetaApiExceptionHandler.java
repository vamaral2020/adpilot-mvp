package br.com.adpilot.integrations.meta;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MetaApiExceptionHandler {
    @ExceptionHandler(MetaApiException.class)
    public ResponseEntity<Map<String, String>> handle(MetaApiException exception) {
        // Nunca incluir payload da Meta, Authorization header ou token nas respostas.
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.errorCode(), "message", exception.getMessage()));
    }
}
