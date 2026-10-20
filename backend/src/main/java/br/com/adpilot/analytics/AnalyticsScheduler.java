package br.com.adpilot.analytics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deliberate opt-in. No scheduled polling until configured locally. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="adpilot.analytics.sync-enabled",havingValue="true")
public class AnalyticsScheduler {
    private final AnalyticsSyncService sync;

    public AnalyticsScheduler(AnalyticsSyncService sync) { this.sync = sync; }

    @Scheduled(cron="${adpilot.analytics.sync-cron:0 5 */3 * * *}",zone="UTC")
    public void scheduled() {
        // The orchestrator isolates Meta and Nuvemshop failures and records safe codes.
        sync.run();
    }
}
