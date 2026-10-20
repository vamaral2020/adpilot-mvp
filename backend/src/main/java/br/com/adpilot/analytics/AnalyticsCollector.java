package br.com.adpilot.analytics;

import br.com.adpilot.integrations.meta.MetaAdsClient;
import br.com.adpilot.integrations.meta.MetaInsightsMetrics;
import br.com.adpilot.integrations.meta.MetaPage;
import br.com.adpilot.integrations.meta.MetaSettings;
import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Purely external READs + strictly whitelisted internal analytical projections.
 * Collect fully before committing anything; pagination or parsing failure
 * must never advance a checkpoint or overwrite complete historical snapshots.
 */
@Component
public class AnalyticsCollector {
    static final int SHOP_MAX_PAGES = 100;
    static final int META_MAX_PAGES = 20;
    static final int MAX_DETAILS = 100;
    private static final DateTimeFormatter BASIC_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final NuvemshopClient shop;
    private final MetaAdsClient meta;
    private final MetaSettings settings;

    public AnalyticsCollector(NuvemshopClient shop, MetaAdsClient meta, MetaSettings settings) {
        this.shop = shop;
        this.meta = meta;
        this.settings = settings;
    }

    public record OrderWindow(List<AnalyticsRecords.Order> rows, int fetched,
                              Instant lower, Instant upper) {}
    public record MetaWindow(List<AnalyticsRecords.Meta> rows, int fetched,
                             String accountId, LocalDate from, LocalDate through) {}
    public record CampaignWindow(List<AnalyticsRecords.Campaign> rows, int fetched,
                                 String accountId, Instant observedAt) {}

    public CampaignWindow campaigns(Instant now) {
        if (!settings.configured()) throw new IllegalStateException("META_NOT_CONFIGURED");
        var result = new ArrayList<AnalyticsRecords.Campaign>();
        var ids = new HashSet<String>();
        var cursors = new HashSet<String>();
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        String after = null;
        int fetched = 0;
        boolean exhausted = false;
        for (int i=0; i<META_MAX_PAGES; i++) {
            MetaPage page = meta.campaigns(100,after);
            if (page.data()==null || (page.data().isEmpty() && page.after()!=null))
                throw new IllegalStateException("INVALID_CAMPAIGN_PAGINATION");
            fetched = Math.addExact(fetched,page.data().size());
            for (Map<String,Object> row : page.data()) {
                if (row==null) throw new IllegalStateException("INVALID_CAMPAIGN_ROW");
                String id = identifier(row.get("id"));
                if (!ids.add(id)) throw new IllegalStateException("DUPLICATE_CAMPAIGN");
                result.add(new AnalyticsRecords.Campaign(settings.accountId(),id,day,
                        safeLabel(row.get("name"),160),safeLabel(row.get("objective"),80),
                        safeLabel(row.get("status"),40),
                        safeLabel(row.get("effective_status"),40),now));
            }
            after = page.after();
            if (after==null) { exhausted=true; break; }
            if (after.isBlank() || after.length()>2048 || !cursors.add(after))
                throw new IllegalStateException("CAMPAIGN_CURSOR_INVALID");
        }
        if (!exhausted) throw new IllegalStateException("CAMPAIGN_PAGE_LIMIT");
        return new CampaignWindow(List.copyOf(result),fetched,settings.accountId(),now);
    }

    private static String safeLabel(Object raw,int limit) {
        if (raw==null) return null;
        if (!(raw instanceof String text) || text.length()>limit)
            throw new IllegalStateException("CAMPAIGN_FIELD_INVALID");
        return text;
    }


    public OrderWindow orders(Instant lastCursor, Instant now) {
        Instant upper = now.minusSeconds(300); // stop five minutes before moving upstream clock
        Instant lower = lastCursor == null ? upper.minusSeconds(30L * 86400)
                : lastCursor.minusSeconds(600); // overlap to cover boundary races
        if (!lower.isBefore(upper)) throw new IllegalStateException("INVALID_ORDER_WINDOW");
        var seen = new HashSet<String>();
        var rows = new ArrayList<AnalyticsRecords.Order>();
        int fetched = 0, detailLookups = 0;
        Long expectedCount = null;
        boolean exhausted = false;
        for (int pageNumber = 1; pageNumber <= SHOP_MAX_PAGES; pageNumber++) {
            NuvemshopPage page = shop.ordersUpdatedBetween(lower, upper, pageNumber, 100);
            if (page.data() == null || (page.data().isEmpty() && page.hasNext())) {
                throw new IllegalStateException("INVALID_SHOP_PAGINATION");
            }
            fetched = Math.addExact(fetched, page.data().size());
            if (page.totalCount() != null) {
                if (page.totalCount() < 0) throw new IllegalStateException("INVALID_SHOP_TOTAL");
                expectedCount = page.totalCount();
            }
            for (Map<String,Object> item : page.data()) {
                if (item == null) throw new IllegalStateException("INVALID_ORDER_DATA");
                String id = identifier(item.get("id"));
                if (!seen.add(id)) throw new IllegalStateException("DUPLICATE_ORDER_IN_WINDOW");
                String status = safeStatus(item.get("status"));
                String payment = safeStatus(item.get("payment_status"));
                Instant updated = requiredInstant(item.get("updated_at"));
                if (updated.isAfter(upper.plusSeconds(60))) {
                    throw new IllegalStateException("ORDER_AFTER_UPPER_BOUND");
                }
                Instant created = optionalInstant(item.get("created_at"));
                Instant paid = optionalInstant(item.get("paid_at"));
                String source = paid == null ? "UNKNOWN" : "LIST";
                BigDecimal total = money(item.get("total"));
                String currency = currency(item.get("currency"));
                boolean eligible = payment.equals("paid") && !status.equals("cancelled");
                if (eligible && paid == null) {
                    if (++detailLookups > MAX_DETAILS) throw new IllegalStateException("ORDER_DETAIL_LIMIT");
                    Map<String,Object> detail = shop.paidOrderDetails(Long.parseLong(id));
                    if (detail == null || !id.equals(identifier(detail.get("id")))
                            || !Objects.equals(item.get("number") == null ? null
                                    : item.get("number").toString(),
                                    detail.get("number") == null ? null : detail.get("number").toString())
                            || !status.equals(detail.get("status"))
                            || !payment.equals(detail.get("payment_status"))
                            || !Objects.equals(currency, currency(detail.get("currency")))
                            || !Objects.equals(total, money(detail.get("total")))) {
                        throw new IllegalStateException("ORDER_DETAIL_MISMATCH");
                    }
                    paid = optionalInstant(detail.get("paid_at"));
                    if (paid == null) throw new IllegalStateException("ORDER_PAID_AT_UNKNOWN");
                    source = "DETAIL";
                }
                if (eligible && (!status.equals("open") && !status.equals("closed")
                        || total == null || currency == null || paid == null)) {
                    throw new IllegalStateException("INVALID_ELIGIBLE_SALE");
                }
                rows.add(new AnalyticsRecords.Order(id,
                        item.get("number") == null ? null : identifier(item.get("number")),
                        status,payment,paid,source,created,updated,total,currency,eligible));
            }
            if (!page.hasNext()) {
                exhausted = true;
                break;
            }
        }
        if (!exhausted) throw new IllegalStateException("SHOP_PAGE_LIMIT");
        // The provider may omit the Link header even on truncated/unstable lists.
        // When a filtered total is supplied, reject incomplete windows.
        if (expectedCount != null && seen.size() < expectedCount) {
            throw new IllegalStateException("SHOP_TOTAL_INCOMPLETE");
        }
        return new OrderWindow(List.copyOf(rows),fetched,lower,upper);
    }

    public MetaWindow insights(Instant now) {
        LocalDate through = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate from = through.minusDays(29); // refresh delayed 28-day attribution
        if (!settings.configured()) throw new IllegalStateException("META_NOT_CONFIGURED");
        Map<String,Object> account = meta.account();
        String currency = currency(account.get("currency"));
        if (currency == null) throw new IllegalStateException("META_CURRENCY_MISSING");
        var rows = new ArrayList<AnalyticsRecords.Meta>();
        var keys = new HashSet<String>();
        var cursors = new HashSet<String>();
        int fetched = 0;
        String after = null;
        boolean exhausted = false;
        for (int pageNumber = 0; pageNumber < META_MAX_PAGES; pageNumber++) {
            MetaPage page = meta.insights(from, through, 100, after);
            if (page.data() == null || (page.data().isEmpty() && page.after() != null))
                throw new IllegalStateException("INVALID_META_PAGINATION");
            fetched += page.data().size();
            for (Map<String,Object> item : page.data()) {
                if (item == null) throw new IllegalStateException("INVALID_META_ROW");
                String campaignId = identifier(item.get("campaign_id"));
                Object rawDate = item.get("date_start");
                LocalDate day;
                try {
                    if (!(rawDate instanceof String date)) throw new IllegalArgumentException();
                    day = LocalDate.parse(date);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("META_DATE_INVALID");
                }
                if (day.isBefore(from) || day.isAfter(through)
                        || item.get("date_stop") != null
                           && !day.toString().equals(item.get("date_stop").toString())) {
                    throw new IllegalStateException("META_PERIOD_MISMATCH");
                }
                if (!keys.add(campaignId + ":" + day)) {
                    throw new IllegalStateException("DUPLICATE_META_DAY");
                }
                MetaInsightsMetrics metrics = MetaInsightsMetrics.fromRow(item);
                rows.add(new AnalyticsRecords.Meta(settings.accountId(),campaignId,day,
                        metrics.spend(),metrics.impressions(),metrics.clicks(),
                        metrics.purchases(),metrics.purchaseValue(),currency));
            }
            after = page.after();
            if (after == null) { exhausted = true; break; }
            if (after.isBlank() || after.length() > 2048 || !cursors.add(after))
                throw new IllegalStateException("META_CURSOR_INVALID");
        }
        if (!exhausted) throw new IllegalStateException("META_PAGE_LIMIT");
        return new MetaWindow(List.copyOf(rows),fetched,settings.accountId(),from,through);
    }

    private static String identifier(Object raw) {
        if (raw == null || !raw.toString().matches("[0-9]{1,30}"))
            throw new IllegalStateException("INVALID_SOURCE_IDENTIFIER");
        return raw.toString();
    }

    private static String safeStatus(Object raw) {
        if (!(raw instanceof String s) || !s.matches("[a-z_]{1,32}"))
            throw new IllegalStateException("INVALID_SOURCE_STATUS");
        return s;
    }

    private static String currency(Object raw) {
        return raw instanceof String s && s.matches("[A-Z]{3}") ? s : null;
    }

    private static BigDecimal money(Object raw) {
        if (raw == null) return null;
        try {
            BigDecimal amount = new BigDecimal(raw.toString()).setScale(2);
            if (amount.signum() < 0 || amount.precision() > 18)
                throw new ArithmeticException();
            return amount;
        } catch (RuntimeException e) {
            throw new IllegalStateException("INVALID_SOURCE_AMOUNT");
        }
    }

    private static Instant requiredInstant(Object raw) {
        Instant value = optionalInstant(raw);
        if (value == null) throw new IllegalStateException("SOURCE_UPDATED_AT_MISSING");
        return value;
    }

    private static Instant optionalInstant(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String text) || text.isBlank())
            throw new IllegalStateException("INVALID_SOURCE_DATE");
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(text, BASIC_OFFSET).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalStateException("INVALID_SOURCE_DATE");
            }
        }
    }
}
