package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Nuvemshop order fields only: not a transaction ledger, refund proof,
 * merchant fee statement, bank payout, or Meta conversion attribution.
 * Provider/method classifications are heuristics of the current order only.
 */
@Service
public class OrderPaymentSignalsService {
    private static final int MAX_PAGES = 20;
    private final NuvemshopClient client;

    public OrderPaymentSignalsService(NuvemshopClient client) {
        this.client = client;
    }

    public record Group(String provider, String paymentMethod, String currency,
            int eligiblePaidOrders, int ordersWithGrossTotal,
            BigDecimal grossOrderTotal, int ordersWithReportedBuyerPaid,
            BigDecimal reportedBuyerPaidTotal, int ordersWithReportedIncludingFees,
            BigDecimal reportedBuyerPaidIncludingFeesTotal,
            int grossAndPaidComparable, int paidLessThanGross,
            int paidGreaterThanGross) {}

    public record Report(boolean upstreamPaginationReconciled,
            boolean financiallyVerified, boolean paymentSettlementVerified,
            boolean ordersMatchedToCampaigns, int pagesFetched,
            int upstreamRowsFetched, int uniqueOrderIds, Long upstreamReportedTotal,
            int duplicateOrderIds, int upstreamEligiblePaidOrders,
            int upstreamCancelledOrders, int upstreamRefundStatusOrders,
            int missingProviderOrders, int unrecognizedProviderOrders,
            int ambiguousProviderOrders, int missingPaymentMethodOrders,
            int missingReportedBuyerPaidOrders, int missingReportedIncludingFeesOrders,
            int invalidMonetaryOrCurrencyOrders,
            int eligibleOrdersWithReportedBuyerPaid,
            int eligibleOrdersWithGrossAndBuyerPaidComparable,
            List<Group> eligiblePaidByProviderMethodCurrency,
            List<String> warnings) {}

    private static final class Totals {
        int count, grossCount, paidCount, inclCount, comparable, less, greater;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal including = BigDecimal.ZERO;

        void add(BigDecimal orderTotal, BigDecimal buyerPaid, BigDecimal includingFees) {
            count++;
            if (orderTotal != null) { grossCount++; gross = gross.add(orderTotal); }
            if (buyerPaid != null) { paidCount++; paid = paid.add(buyerPaid); }
            if (includingFees != null) {
                inclCount++; including = including.add(includingFees);
            }
            if (orderTotal != null && buyerPaid != null) {
                comparable++;
                int comparison = buyerPaid.compareTo(orderTotal);
                if (comparison < 0) less++;
                if (comparison > 0) greater++;
            }
        }
    }

    public Report inspect(int pageSize, int maxPages) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES)
            throw new NuvemshopException(HttpStatus.BAD_REQUEST,"INVALID_PAGINATION",
                    "pageSize entre 1 e 100 e maxPages entre 1 e 20.");

        var seen = new HashSet<String>();
        var totals = new TreeMap<String,Totals>();
        Long expected = null;
        boolean present = true, stable = true, exhausted = false;
        int pages = 0, fetched = 0, duplicates = 0;
        int eligible = 0, cancelled = 0, refunds = 0;
        int missingGateway = 0, unrecognizedGateway = 0, ambiguousGateway = 0;
        int missingMethod = 0, missingPaid = 0, missingWithFees = 0, invalidMoney = 0;
        int eligibleWithBuyerPaid = 0, eligibleComparable = 0;

        for (int p=1; p<=maxPages; p++) {
            var page = client.orderPaymentSignals(p,pageSize);
            pages++;
            if (page.data()==null || page.data().size()>pageSize
                    || page.data().isEmpty() && page.hasNext())
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY,"NUVEMSHOP_INVALID_PAGINATION",
                        "Paginacao de pedidos inconsistente.");
            fetched = Math.addExact(fetched,page.data().size());
            if (page.totalCount()==null) present=false;
            else if (page.totalCount()<0) stable=false;
            else if (expected==null) expected=page.totalCount();
            else if (!expected.equals(page.totalCount())) stable=false;

            for (Map<String,Object> row : page.data()) {
                if (row == null || row.get("id") == null
                        || !row.get("id").toString().matches("[0-9]{1,30}"))
                    throw invalidRow();
                String id=row.get("id").toString();
                String state=safeState(row.get("status"));
                String payment=safeState(row.get("payment_status"));
                if (state==null || payment==null) throw invalidRow();
                if (!seen.add(id)) { duplicates++; continue; }
                if ("cancelled".equals(state)) cancelled++;
                if ("refunded".equals(payment) || "partially_refunded".equals(payment)) refunds++;

                String provider = provider(row.get("gateway"), row.get("gateway_name"));
                String method = method(row.get("payment_details"));
                if ("MISSING".equals(provider)) missingGateway++;
                if ("UNRECOGNIZED".equals(provider)) unrecognizedGateway++;
                if ("AMBIGUOUS".equals(provider)) ambiguousGateway++;
                if ("MISSING".equals(method)) missingMethod++;
                BigDecimal paid = money(row.get("total_paid_by_customer"));
                BigDecimal includingFees = money(row.get("total_paid_by_customer_including_fees"));
                BigDecimal gross = money(row.get("total"));
                String currency = currency(row.get("currency"));
                if (paid == null) missingPaid++;
                if (includingFees == null) missingWithFees++;
                if ((row.get("total") != null && gross == null)
                        || (row.get("total_paid_by_customer") != null && paid == null)
                        || (row.get("total_paid_by_customer_including_fees") != null && includingFees == null)
                        || (row.get("currency") != null && currency == null)) invalidMoney++;
                if (!"paid".equals(payment) ||
                        !("open".equals(state) || "closed".equals(state))) continue;
                eligible++;
                if (paid != null) eligibleWithBuyerPaid++;
                if (gross != null && paid != null && currency != null) eligibleComparable++;
                if (currency == null) continue; // Never sum money without a valid currency.
                String key=provider+"|"+method+"|"+currency;
                totals.computeIfAbsent(key,k->new Totals()).add(gross,paid,includingFees);
            }
            if (!page.hasNext()) { exhausted=true; break; }
        }

        boolean reconciled=exhausted && present && stable && duplicates==0
                && expected!=null && expected==seen.size();
        var groups=new ArrayList<Group>();
        for (var entry:totals.entrySet()) {
            var labels=entry.getKey().split("\\|",3);
            var t=entry.getValue();
            groups.add(new Group(labels[0],labels[1],labels[2],t.count,
                    t.grossCount,t.gross,t.paidCount,t.paid,t.inclCount,t.including,
                    t.comparable,t.less,t.greater));
        }
        var warnings=new ArrayList<String>();
        warnings.add("BUYER_REPORTED_AMOUNTS_NOT_PROVIDER_SETTLEMENT");
        warnings.add("GATEWAY_IS_ORDER_LEVEL_HINT_NOT_PROVEN_TRANSACTION_PROCESSOR");
        warnings.add("PAYMENT_METHOD_IS_REPORTED_NOT_VERIFIED");
        warnings.add("CUSTOMER_FEES_NOT_MERCHANT_PROCESSING_FEES");
        warnings.add("REFUNDS_CHARGEBACKS_AND_PAYOUTS_NOT_VERIFIED");
        warnings.add("ORDER_LIST_NOT_POINT_IN_TIME");
        warnings.add("NO_ORDER_TO_META_CAMPAIGN_ASSOCIATION");
        if (!reconciled) warnings.add("UPSTREAM_PAGINATION_NOT_RECONCILED");
        if (missingGateway>0 || unrecognizedGateway>0 || ambiguousGateway>0)
            warnings.add("PROVIDER_IDENTIFICATION_INCOMPLETE");
        if (missingMethod>0) warnings.add("PAYMENT_METHOD_INCOMPLETE");
        if (missingPaid>0 || missingWithFees>0 || invalidMoney>0)
            warnings.add("BUYER_REPORTED_PAYMENT_FIELDS_INCOMPLETE");
        return new Report(reconciled,false,false,false,pages,fetched,seen.size(),
                present&&stable ? expected : null,duplicates,eligible,cancelled,refunds,
                missingGateway,unrecognizedGateway,ambiguousGateway,missingMethod,
                missingPaid,missingWithFees,invalidMoney,eligibleWithBuyerPaid,
                eligibleComparable,List.copyOf(groups),List.copyOf(warnings));
    }

    private static String safeState(Object raw) {
        return raw instanceof String value && value.matches("[a-z_]{1,32}") ? value : null;
    }

    private static String provider(Object gateway, Object name) {
        String a=providerToken(gateway);
        String b=providerToken(name);
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) return "AMBIGUOUS";
        if (!a.isEmpty()) return a;
        if (!b.isEmpty()) return b;
        if (gateway==null && name==null) return "MISSING";
        return "UNRECOGNIZED";
    }

    private static String providerToken(Object raw) {
        if (!(raw instanceof String value) || value.length()>120) return "";
        String key=value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
        if (key.contains("pagarme")) return "PAGARME";
        if (key.contains("mercadopago")) return "MERCADO_PAGO";
        if (key.contains("nuvempago") || key.contains("nuvempay")) return "NUVEM_PAGO";
        if (key.equals("offline")) return "OFFLINE";
        if (key.equals("internal")) return "INTERNAL";
        return "";
    }

    private static String method(Object paymentDetails) {
        if (!(paymentDetails instanceof Map<?,?> details)) return "MISSING";
        if (!(details.get("method") instanceof String raw) || raw.length()>40)
            return "MISSING";
        return switch (raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","")) {
            case "creditcard", "card" -> "CREDIT_CARD";
            case "debitcard" -> "DEBIT_CARD";
            case "pix" -> "PIX";
            case "boleto" -> "BOLETO";
            case "banktransfer", "wiretransfer" -> "BANK_TRANSFER";
            case "cash" -> "CASH";
            case "custom" -> "CUSTOM";
            default -> "OTHER";
        };
    }

    private static String currency(Object raw) {
        return raw instanceof String value && value.matches("[A-Z]{3}") ? value : null;
    }

    private static BigDecimal money(Object raw) {
        if (!(raw instanceof String || raw instanceof Number)) return null;
        try {
            String value=raw.toString();
            if (value.length()>40 || !value.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
            BigDecimal number=new BigDecimal(value).setScale(2,RoundingMode.UNNECESSARY);
            return number.precision() <= 18 ? number : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static NuvemshopException invalidRow() {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY,"NUVEMSHOP_INVALID_ORDER_DATA",
                "A listagem de pedidos retornou dados invalidos.");
    }
}
