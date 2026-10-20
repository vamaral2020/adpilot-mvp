package br.com.adpilot.integrations.nuvemshop;

import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/nuvemshop")
public class NuvemshopController {
    private final NuvemshopAuthService oauth;
    private final NuvemshopClient client;
    private final NuvemshopSettings settings;

    public NuvemshopController(NuvemshopAuthService oauth, NuvemshopClient client,
                               NuvemshopSettings settings) {
        this.oauth = oauth;
        this.client = client;
        this.settings = settings;
    }

    @GetMapping("/status")
    public ResponseEntity<NuvemshopAuthService.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(oauth.status());
    }

    @GetMapping("/oauth/start")
    public ResponseEntity<NuvemshopAuthService.Authorization> start() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(oauth.begin());
    }

    // Public OAuth callback: authorization relies on a 256-bit, one-time CSRF state.
    // Redirect strips the short-lived code from the browser URL immediately.
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state) {
        oauth.complete(code, state);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(settings.dashboardUrl()))
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/products")
    public NuvemshopPage products(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "30") int perPage) {
        return client.products(page, perPage);
    }

    @GetMapping("/orders")
    public NuvemshopPage orders(@RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "30") int perPage) {
        return client.orders(page, perPage);
    }
}
