package br.com.adpilot.integrations.meta;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MetaApiExceptionHandler {
    @ExceptionHandler(MetaApiException.class)
    public ResponseEntity<Map<String, Object>> handle(MetaApiException exception) {
        // Apenas campos sob nosso controle, sem payload original da Meta.
        var response = new LinkedHashMap<String, Object>();
        response.put("code", exception.errorCode());
        response.put("message", exception.getMessage());
        if (exception.upstreamStatus() != null) {
            response.put("meta_http_status", exception.upstreamStatus());
        }
        if (exception.metaCode() != null) {
            response.put("meta_error_code", exception.metaCode());
        }
        if (exception.metaSubcode() != null) {
            response.put("meta_error_subcode", exception.metaSubcode());
        }
        return ResponseEntity.status(exception.status()).body(response);
    }
}
