package br.com.adpilot.commerce;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commerce")
public class CommerceController {
    private final CommerceSummaryService service;

    public CommerceController(CommerceSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<CommerceSummary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until,
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestParam(defaultValue = "20") int maxPages) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.summary(since, until, pageSize, maxPages));
    }
}
