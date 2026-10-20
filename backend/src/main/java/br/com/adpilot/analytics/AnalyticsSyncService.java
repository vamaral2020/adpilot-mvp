package br.com.adpilot.analytics;

import br.com.adpilot.integrations.meta.MetaSettings;
import br.com.adpilot.integrations.nuvemshop.NuvemshopAuthService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs independently per source; no cross-provider distributed transaction.
 * Each provider commits data plus its checkpoint in one DB transaction.
 * External APIs are called outside DB transactions.
 */
@Service
public class AnalyticsSyncService {
    private final AnalyticsSyncStore store;
    private final AnalyticsCollector collector;
    private final MetaSettings metaSettings;
    private final NuvemshopAuthService shopAuth;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public AnalyticsSyncService(AnalyticsSyncStore store, AnalyticsCollector collector,
            MetaSettings metaSettings, NuvemshopAuthService shopAuth,
            @Value("${adpilot.analytics.sync-enabled:false}") boolean enabled) {
        this.store = store;
        this.collector = collector;
        this.metaSettings = metaSettings;
        this.shopAuth = shopAuth;
        this.enabled = enabled;
    }

    public record ProviderResult(String provider, String resource, String status,
            Integer fetched, Integer stored, String code) {}

    public record Report(boolean enabled, List<ProviderResult> results) {}

    public Report run() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A coleta esta desabilitada. Habilite ADPILOT_ANALYTICS_SYNC_ENABLED.");
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Outra sincronizacao esta em andamento.");
        }
        try {
            var results = new ArrayList<ProviderResult>();
            if ("CONNECTED".equals(shopAuth.status().status())) {
                results.add(runOrders());
            } else {
                results.add(new ProviderResult("NUVEMSHOP","ORDERS","SKIPPED",null,null,
                        "SHOP_NOT_CONNECTED"));
            }
            if (metaSettings.configured()) {
                results.add(runMeta());
            } else {
                results.add(new ProviderResult("META_ADS","INSIGHTS_DAILY","SKIPPED",null,null,
                        "META_NOT_CONFIGURED"));
            }
            return new Report(true, List.copyOf(results));
        } finally {
            running.set(false);
        }
    }

    private ProviderResult runOrders() {
        final String provider="NUVEMSHOP", resource="ORDERS";
        long runId = store.begin(provider,resource);
        try {
            Instant now = Instant.now();
            Instant cursor = store.cursor(provider,resource);
            var result = collector.orders(cursor,now);
            store.saveOrders(runId,result.rows(),result.upper(),result.fetched());
            return new ProviderResult(provider,resource,"SUCCEEDED",result.fetched(),
                    result.rows().size(),null);
        } catch (RuntimeException e) {
            store.failed(runId,"ORDER_SYNC_FAILED");
            return new ProviderResult(provider,resource,"FAILED",null,null,"ORDER_SYNC_FAILED");
        }
    }

    private ProviderResult runMeta() {
        final String provider="META_ADS", resource="INSIGHTS_DAILY";
        long runId = store.begin(provider,resource);
        try {
            Instant now = Instant.now();
            var result = collector.insights(now);
            store.saveMeta(runId,result.rows(),result.accountId(),result.from(),
                    result.through(),result.through().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    result.fetched());
            return new ProviderResult(provider,resource,"SUCCEEDED",result.fetched(),
                    result.rows().size(),null);
        } catch (RuntimeException e) {
            store.failed(runId,"META_SYNC_FAILED");
            return new ProviderResult(provider,resource,"FAILED",null,null,"META_SYNC_FAILED");
        }
    }

    public boolean enabled() { return enabled; }
}
