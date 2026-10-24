package br.com.adpilot.integrations.stone;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Bounded read-only EXPLORATORY correlation by exact numeric order reference.
 * This service never persists or emits IDs, raw codes, transaction details,
 * PII, financial totals or per-order outcomes.
 */
@Service
public class StoneNuvemCorrelationService {
    private final StoneHistoryClient stone;
    private final NuvemshopClient nuvem;

    public StoneNuvemCorrelationService(StoneHistoryClient stone,NuvemshopClient nuvem) {
        this.stone=stone;
        this.nuvem=nuvem;
    }

    public record Population(int pagesFetched,int rowsFetched,int uniqueIds,
            int duplicateIds,Long reportedTotal,boolean paginationReconciled) {}

    public record Report(String source,String keyEnvironment,
            Population providerOrders,Population providerCharges,Population nuvemshopOrders,
            int providerOrdersWithCode,int providerCodesUnsupportedForExactMatch,
            int providerCodesWithoutCandidate,int providerCodesWithAmbiguousTargets,
            int providerOrdersWithDuplicateTarget,
            int candidateByNuvemshopId,int candidateByNuvemshopNumber,
            int candidateByBoth,int uniqueOneToOneCandidates,
            int amountsComparable,int amountsEqual,int amountsDifferent,int amountsUnavailable,
            int paidFlagsComparable,int paidFlagsAligned,int paidFlagsDifferent,
            int creationTimesComparable,int creationTimesWithin48Hours,
            int creationTimesOver48Hours,
            int paymentMethodsComparable,int paymentMethodsEqual,int paymentMethodsDifferent,
            int candidatesWithoutObservedProviderCharge,int candidatesWithMultipleProviderCharges,
            boolean financiallyVerified,boolean paymentSettlementVerified,
            boolean ordersMatchedToNuvemshop,boolean ordersMatchedToCampaigns,
            List<String> warnings, AuditBreakdown audit) {}

    /**
     * PR047: coarse, aggregate-only quality evidence. Never serialize raw
     * references, individual amounts, exact timestamps or gateway strings.
     */
    public record AuditBreakdown(Map<String,Integer> unsupportedCodeFormats,
            int duplicateNuvemshopTargetGroups,
            Map<String,Integer> grossDifferenceDirectionAndMagnitude,
            int grossDifferencesAlsoPaymentStateMismatch,
            int grossDifferencesAlsoMethodMismatch,
            int grossDifferencesWithOtherGatewayHint,
            Map<String,Integer> candidateProviderCreatedAtFields,
            Map<String,Integer> candidateNuvemshopCreatedAtFields,
            Map<String,Integer> candidateGatewayHints,
            List<String> cautions) {}

    private record ShopOrder(String id,String number,String paymentStatus,String currency,
            Long cents,Instant createdAt,String paymentMethod,
            String createdAtFieldStatus,String gatewayHint) {}
    private record Candidate(StoneHistoryClient.CrossReferenceOrder provider,
            ShopOrder shop,boolean byId,boolean byNumber) {}

    private static final class Acc {
        int pages,rows,duplicates;
        Long expected;
        boolean stable=true,present=true,finished=false;
        Set<String> seen=new HashSet<>();
        void add(List<String> ids,Long total,boolean pagingPresent,boolean next,
                int pageSize) {
            pages++;
            if(ids.size()>pageSize || ids.isEmpty() && next) throw badStone();
            rows+=ids.size();
            if(!pagingPresent || total==null) present=false;
            if(total!=null) {
                if(total<0) stable=false;
                else if(expected==null) expected=total;
                else if(!expected.equals(total)) stable=false;
            }
            for(String id:ids) if(!seen.add(id)) duplicates++;
            if(!next) finished=true;
        }
        Population report() {
            return new Population(pages,rows,seen.size(),duplicates,
                    present&&stable?expected:null,
                    finished&&present&&stable&&duplicates==0
                            &&expected!=null&&expected==seen.size());
        }
    }

    public Report inspect(int pageSize,int maxPages) {
        if(pageSize<1 || pageSize>50 || maxPages<1 || maxPages>10)
            throw new StoneChargeException(HttpStatus.BAD_REQUEST,
                    "INVALID_CORRELATION_PAGINATION","pageSize 1..50, maxPages 1..10.");

        var shopAcc=new Acc();
        Map<String,ShopOrder> shops=new HashMap<>();
        Map<String,Set<String>> byNumber=new HashMap<>();
        for(int p=1;p<=maxPages;p++) {
            NuvemshopPage page=nuvem.orderCrossProviderCandidates(p,pageSize);
            if(page==null||page.data()==null||page.data().size()>pageSize)
                throw badNuvem();
            var pageIds=new ArrayList<String>();
            for(Map<String,Object> raw:page.data()) {
                if(raw==null) throw badNuvem();
                String id=digits(raw.get("id"));
                String number=digits(raw.get("number"));
                if(id==null || number==null) throw badNuvem();
                pageIds.add(id);
                if(shops.containsKey(id)) continue;
                ShopOrder row=new ShopOrder(id,number,status(raw.get("payment_status")),
                        "BRL".equals(raw.get("currency"))?"BRL":"UNKNOWN",
                        amountFromReais(raw.get("total")),timestamp(raw.get("created_at")),
                        method(raw.get("payment_details")),
                        dateFieldStatus(raw.get("created_at")),
                        gatewayHint(raw.get("gateway"),raw.get("gateway_name")));
                shops.put(id,row);
                byNumber.computeIfAbsent(number,k->new HashSet<>()).add(id);
            }
            shopAcc.add(pageIds,page.totalCount(),true,page.hasNext(),pageSize);
            if(!page.hasNext()) break;
        }

        var providerAcc=new Acc();
        List<StoneHistoryClient.CrossReferenceOrder> providers=new ArrayList<>();
        for(int p=1;p<=maxPages;p++) {
            var page=stone.correlationOrders(p,pageSize);
            var pageIds=new ArrayList<String>();
            var uniqueOnPage=new HashSet<String>();
            for(var row:page.data()) {
                pageIds.add(row.id());
                if(!providerAcc.seen.contains(row.id()) && uniqueOnPage.add(row.id()))
                    providers.add(row);
            }
            providerAcc.add(pageIds,page.total(),page.pagingMetadataPresent(),
                    page.hasNext(),pageSize);
            if(!page.hasNext()) break;
        }

        var chargeAcc=new Acc();
        Map<String,List<StoneHistoryClient.ProviderCharge>> chargesByOrder=new HashMap<>();
        for(int p=1;p<=maxPages;p++) {
            var page=stone.charges(p,pageSize);
            var pageIds=new ArrayList<String>();
            var uniqueOnPage=new HashSet<String>();
            for(var charge:page.data()) {
                pageIds.add(charge.id());
                if(charge.providerOrderId()!=null && !chargeAcc.seen.contains(charge.id())
                        && uniqueOnPage.add(charge.id()))
                    chargesByOrder.computeIfAbsent(charge.providerOrderId(),
                            k->new ArrayList<>()).add(charge);
            }
            chargeAcc.add(pageIds,page.total(),page.pagingMetadataPresent(),
                    page.hasNext(),pageSize);
            if(!page.hasNext()) break;
        }

        int withCode=0,unsupported=0,noMatch=0,ambiguous=0;
        var unsupportedFormats=new java.util.TreeMap<String,Integer>();
        var candidates=new ArrayList<Candidate>();
        var shopUsage=new HashMap<String,Integer>();
        for(var provider:providers) {
            String code=provider.code();
            if(code==null || code.isBlank()) continue;
            withCode++;
            // Exact canonical numeric IDs only: no trimming, prefixes or fuzzy match.
            if(!code.matches("[0-9]{1,30}")) {
                unsupported++;
                unsupportedFormats.merge(unsupportedCodeCategory(code),1,Integer::sum);
                continue;
            }
            Set<String> matches=new HashSet<>();
            if(shops.containsKey(code)) matches.add(code);
            matches.addAll(byNumber.getOrDefault(code,Set.of()));
            if(matches.isEmpty()) {noMatch++;continue;}
            if(matches.size()!=1) {ambiguous++;continue;}
            String id=matches.iterator().next();
            ShopOrder shop=shops.get(id);
            candidates.add(new Candidate(provider,shop,code.equals(id),
                    code.equals(shop.number())));
            shopUsage.merge(id,1,Integer::sum);
        }
        int collision=0,viaId=0,viaNumber=0,viaBoth=0,unique=0;
        int moneyCompared=0,moneyEqual=0,moneyDifferent=0,moneyUnknown=0;
        int stateCompared=0,stateAligned=0,stateDifferent=0;
        int timesCompared=0,timesNear=0,timesFar=0;
        int methodsCompared=0,methodsEqual=0,methodsDifferent=0;
        int noCharge=0,multipleCharges=0;
        int differencesAlsoStatus=0,differencesAlsoMethod=0,differencesWithOtherGateway=0;
        var differenceBands=new java.util.TreeMap<String,Integer>();
        var providerDateStates=new java.util.TreeMap<String,Integer>();
        var shopDateStates=new java.util.TreeMap<String,Integer>();
        var gatewayHints=new java.util.TreeMap<String,Integer>();
        int duplicateTargetGroups=(int)shopUsage.values().stream().filter(v->v>1).count();
        for(var c:candidates) {
            if(shopUsage.get(c.shop().id())!=1) {collision++;continue;}
            unique++;
            providerDateStates.merge(c.provider().createdAtFieldStatus(),1,Integer::sum);
            shopDateStates.merge(c.shop().createdAtFieldStatus(),1,Integer::sum);
            gatewayHints.merge(c.shop().gatewayHint(),1,Integer::sum);
            boolean amountMismatch=false,paidStateMismatch=false,methodMismatch=false;
            if(c.byId() && c.byNumber()) viaBoth++;
            else if(c.byId()) viaId++;
            else viaNumber++;

            if(c.provider().amountCents()==null || c.shop().cents()==null
                    || !"BRL".equals(c.shop().currency())
                    || !"BRL".equals(c.provider().currency())) moneyUnknown++;
            else {
                moneyCompared++;
                if(c.provider().amountCents().equals(c.shop().cents())) moneyEqual++;
                else {
                    moneyDifferent++;
                    amountMismatch=true;
                    differenceBands.merge(differenceCategory(c.provider().amountCents(),
                            c.shop().cents()),1,Integer::sum);
                }
            }
            if(!"UNKNOWN".equals(c.provider().status())
                    && !"UNKNOWN".equals(c.shop().paymentStatus())) {
                stateCompared++;
                if(("PAID".equals(c.provider().status()))
                        ==("PAID".equals(c.shop().paymentStatus()))) stateAligned++;
                else {stateDifferent++;paidStateMismatch=true;}
            }
            if(c.shop().createdAt()!=null && c.provider().createdAt()!=null) {
                timesCompared++;
                long hours=Math.abs(Duration.between(c.shop().createdAt(),
                        c.provider().createdAt()).toHours());
                if(hours<=48) timesNear++;
                else timesFar++;
            }
            var linked=chargesByOrder.getOrDefault(c.provider().id(),List.of());
            if(linked.isEmpty()) noCharge++;
            else if(linked.size()>1) multipleCharges++;
            else {
                String fromProvider=linked.getFirst().method();
                if(!"UNKNOWN".equals(fromProvider)
                        && !"UNKNOWN".equals(c.shop().paymentMethod())) {
                    methodsCompared++;
                    if(fromProvider.equals(c.shop().paymentMethod())) methodsEqual++;
                    else {methodsDifferent++;methodMismatch=true;}
                }
            }
            if(amountMismatch) {
                if(paidStateMismatch) differencesAlsoStatus++;
                if(methodMismatch) differencesAlsoMethod++;
                if("OTHER_REPORTED".equals(c.shop().gatewayHint())
                        || "CONFLICTING_LABELS".equals(c.shop().gatewayHint()))
                    differencesWithOtherGateway++;
            }
        }

        var warnings=new ArrayList<String>(List.of(
                "EXACT_CODE_MATCH_IS_A_CANDIDATE_NOT_VERIFIED_ORDER_LINK",
                "CROSS_PROVIDER_RESULTS_NOT_POINT_IN_TIME",
                "NO_PAYMENT_CAPTURE_SETTLEMENT_REFUND_FEE_OR_PAYOUT_VERIFICATION",
                "NO_META_ATTRIBUTION",
                "RESPONSE_CONTAINS_AGGREGATES_ONLY"));
        if(!shopAcc.report().paginationReconciled()
                ||!providerAcc.report().paginationReconciled()
                ||!chargeAcc.report().paginationReconciled())
            warnings.add("INCOMPLETE_OR_UNSTABLE_PAGINATION");
        if(unsupported>0) warnings.add("PROVIDER_EXTERNAL_CODES_NOT_CANONICAL_NUMERIC");
        if(ambiguous>0 || collision>0) warnings.add("AMBIGUOUS_OR_DUPLICATE_CORRELATION");
        if(moneyDifferent>0) warnings.add("GROSS_AMOUNT_DISAGREEMENT_ON_CANDIDATES");
        if(stateDifferent>0) warnings.add("PAID_STATUS_DISAGREEMENT_ON_CANDIDATES");
        if(timesFar>0) warnings.add("CREATION_TIME_OVER_48_HOURS_ON_CANDIDATES");
        if(methodsDifferent>0) warnings.add("PAYMENT_METHOD_DISAGREEMENT_ON_CANDIDATES");
        if(providerDateStates.getOrDefault("ABSENT_OR_NULL",0)>0
                || providerDateStates.getOrDefault("UNPARSEABLE",0)>0
                || shopDateStates.getOrDefault("ABSENT_OR_NULL",0)>0
                || shopDateStates.getOrDefault("UNPARSEABLE",0)>0)
            warnings.add("ORDER_CREATION_TIMESTAMPS_INCOMPLETE_OR_UNPARSEABLE");
        if(gatewayHints.getOrDefault("OTHER_REPORTED",0)>0
                || gatewayHints.getOrDefault("CONFLICTING_LABELS",0)>0)
            warnings.add("CANDIDATE_NUVEMSHOP_GATEWAY_MAY_DIFFER_FROM_STONE");

        var audit=new AuditBreakdown(Map.copyOf(unsupportedFormats),duplicateTargetGroups,
                Map.copyOf(differenceBands),differencesAlsoStatus,differencesAlsoMethod,
                differencesWithOtherGateway,Map.copyOf(providerDateStates),
                Map.copyOf(shopDateStates),Map.copyOf(gatewayHints),
                List.of("AMOUNT_IS_GROSS_ORDER_FIELD_NOT_SETTLEMENT",
                        "GATEWAY_IS_ORDER_HINT_NOT_PAYMENT_PROCESSOR_PROOF",
                        "NUMERIC_CODE_IS_UNVERIFIED_LINK_EVIDENCE",
                        "TIMESTAMPS_ARE_NOT_ATOMIC_ACROSS_SYSTEMS"));

        return new Report("PAGARME_CORE_V5_NUVEMSHOP",
                stone.keyEnvironment(),providerAcc.report(),chargeAcc.report(),
                shopAcc.report(),withCode,unsupported,noMatch,ambiguous,collision,
                viaId,viaNumber,viaBoth,unique,moneyCompared,moneyEqual,moneyDifferent,
                moneyUnknown,stateCompared,stateAligned,stateDifferent,
                timesCompared,timesNear,timesFar,
                methodsCompared,methodsEqual,methodsDifferent,noCharge,multipleCharges,
                false,false,false,false,List.copyOf(warnings),audit);
    }

    private static String digits(Object input) {
        if(input==null) return null;
        if(!(input instanceof Number || input instanceof String)) return null;
        String s=input.toString();
        return s.matches("[0-9]{1,30}")?s:null;
    }
    private static String status(Object raw) {
        if(!(raw instanceof String s) || !s.matches("[a-z_]{1,32}")) return "UNKNOWN";
        return s.toUpperCase(Locale.ROOT);
    }
    private static String method(Object raw) {
        if(!(raw instanceof Map<?,?> map)
                || !(map.get("method") instanceof String s)||s.length()>40) return "UNKNOWN";
        return switch(s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","")) {
            case "creditcard","card" -> "CREDIT_CARD";
            case "debitcard" -> "DEBIT_CARD";
            case "pix" -> "PIX";
            case "boleto" -> "BOLETO";
            case "banktransfer" -> "BANK_TRANSFER";
            default -> "UNKNOWN";
        };
    }
    private static Long amountFromReais(Object input) {
        if(!(input instanceof Number || input instanceof String)) return null;
        try {
            String s=input.toString();
            if(s.length()>40) return null;
            var value=new BigDecimal(s).setScale(2,RoundingMode.UNNECESSARY);
            if(value.signum()<0) return null;
            long cents=value.movePointRight(2).longValueExact();
            return cents<=Integer.MAX_VALUE?cents:null;
        } catch(RuntimeException e) {return null;}
    }
    private static Instant timestamp(Object input) {
        if(!(input instanceof String s)||s.length()>40) return null;
        try {return OffsetDateTime.parse(s).toInstant();}
        catch(DateTimeParseException e) {return null;}
    }
    private static StoneChargeException badStone() {
        return new StoneChargeException(HttpStatus.BAD_GATEWAY,
                "STONE_CORRELATION_INVALID_PAGING","Paginacao Stone inconsistente.");
    }
    private static NuvemshopException badNuvem() {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY,
                "NUVEMSHOP_CORRELATION_INVALID_RESPONSE","Dados de pedidos inconsistentes.");
    }
}
