package br.com.adpilot.integrations.nuvemshop;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class NuvemshopExceptionHandler {
    @ExceptionHandler(NuvemshopException.class)
    public ResponseEntity<Map<String, Object>> handle(NuvemshopException e) {
        // Never include upstream body, code, state, client_secret or bearer tokens.
        var response = new LinkedHashMap<String, Object>();
        response.put("code", e.code());
        response.put("message", e.getMessage());
        if (e.upstreamStatus() != null) response.put("upstream_http_status", e.upstreamStatus());
        return ResponseEntity.status(e.status())
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
