package br.com.adpilot.integrations.stone;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Strict, bounded, GET-only historical discovery of Pagar.me Core v5
 * provider orders and charges. Untrusted upstream objects are reduced
 * to an allowlist before leaving this class; never log provider bodies.
 */
@Component
public class StoneHistoryClient {
    private static final URI BASE=URI.create("https://api.pagar.me/core/v5");
    private static final Set<String> ORDER_STATES=Set.of(
            "pending","paid","canceled","failed","closed");
    private static final Set<String> CHARGE_STATES=Set.of(
            "pending","paid","canceled","processing","failed",
            "overpaid","underpaid","chargedback");
    private static final Set<String> METHODS=Set.of(
            "credit_card","debit_card","pix","boleto","voucher","bank_transfer");

    private final String secretKey;
    private final URI base;
    private final RestClient http;

    @Autowired
    public StoneHistoryClient(@Value("${adpilot.stone.secret-key:}") String secretKey,
            RestClient.Builder builder) {
        this(secretKey,builder,BASE);
    }

    // Restrict custom host injection to package-private local HTTP tests only.
    StoneHistoryClient(String secretKey,RestClient.Builder builder,URI base) {
        this.secretKey=secretKey==null?"":secretKey;
        this.base=base;
        var transport=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http=builder.requestFactory(factory).build();
    }

    public record ProviderOrder(String id,String status,boolean codePresent) {}
    /** Raw reference stays in memory only and MUST NOT be serialized by controllers. */
    public record CrossReferenceOrder(String id,String code,String status,
            String currency,Long amountCents,Instant createdAt,String createdAtFieldStatus) {
        // Compatibility for PR046 fixtures and existing callers.
        public CrossReferenceOrder(String id,String code,String status,
                String currency,Long amountCents,Instant createdAt) {
            this(id,code,status,currency,amountCents,createdAt,
                    createdAt==null?"ABSENT_OR_NULL":"PARSED");
        }
    }

    public record ProviderCharge(String id,String providerOrderId,String status,
            String method,boolean amountPresent,boolean paidAmountPresent) {}
    public record Page<T>(List<T> data,Long total,boolean hasNext,
            boolean pagingMetadataPresent) {}

    public String keyEnvironment() {
        if(secretKey.startsWith("sk_test_")) return "SANDBOX";
        if(secretKey.startsWith("sk_live_")) return "PRODUCTION";
        return "UNDETERMINED";
    }

    public boolean configured() {
        return secretKey.startsWith("sk_") && secretKey.length()>=8
                && secretKey.equals(secretKey.trim());
    }

    public Page<ProviderOrder> orders(int page,int size) {
        Map<?,?> response=fetch("orders",page,size);
        var records=new ArrayList<ProviderOrder>();
        for(Map<?,?> row:data(response)) {
            String id=identifier(row.get("id"),"or_");
            records.add(new ProviderOrder(id,
                    safeEnum(row.get("status"),ORDER_STATES),
                    row.get("code") instanceof String code && !code.isBlank()));
        }
        return page(response,records);
    }

    /** Strictly projected order data for a bounded cross-provider diagnostic. */
    public Page<CrossReferenceOrder> correlationOrders(int page,int size) {
        Map<?,?> response=fetch("orders",page,size);
        var records=new ArrayList<CrossReferenceOrder>();
        for(Map<?,?> row:data(response)) {
            String id=identifier(row.get("id"),"or_");
            String code=row.get("code") instanceof String s && s.length()<=52 ? s:null;
            records.add(new CrossReferenceOrder(id,code,
                    safeEnum(row.get("status"),ORDER_STATES),
                    "BRL".equals(row.get("currency"))?"BRL":"UNKNOWN",
                    amountCents(row.get("amount")),date(row.get("created_at")),
                    dateFieldStatus(row.get("created_at"))));
        }
        return page(response,records);
    }

    private static Long amountCents(Object raw) {
        if(raw==null) return null;
        if(!(raw instanceof Integer || raw instanceof Long)) return null;
        long value=((Number)raw).longValue();
        return value>=0 && value<=Integer.MAX_VALUE?value:null;
    }

    private static Instant date(Object raw) {
        if(!(raw instanceof String value)||value.length()>40) return null;
        try { return Instant.parse(value); }
        catch(DateTimeParseException ignored) {
            try { return java.time.OffsetDateTime.parse(value).toInstant(); }
            catch(DateTimeParseException e) { return null; }
        }
    }

    private static String dateFieldStatus(Object raw) {
        if(raw==null) return "ABSENT_OR_NULL";
        return date(raw)==null?"UNPARSEABLE":"PARSED";
    }

    public Page<ProviderCharge> charges(int page,int size) {
        Map<?,?> response=fetch("charges",page,size);
        var records=new ArrayList<ProviderCharge>();
        for(Map<?,?> row:data(response)) {
            String id=identifier(row.get("id"),"ch_");
            String parent=optionalOrderId(row);
            records.add(new ProviderCharge(id,parent,
                    safeEnum(row.get("status"),CHARGE_STATES),
                    safeEnum(row.get("payment_method"),METHODS),
                    row.get("amount")!=null,row.get("paid_amount")!=null));
        }
        return page(response,records);
    }

    private Map<?,?> fetch(String resource,int page,int size) {
        if(!("orders".equals(resource)||"charges".equals(resource))
                || page<1 || page>10 || size<1 || size>50)
            throw new StoneChargeException(HttpStatus.BAD_REQUEST,
                    "STONE_INVALID_PAGINATION","Parametros de paginacao invalidos.");
        if(!configured())
            throw new StoneChargeException(HttpStatus.SERVICE_UNAVAILABLE,
                    "STONE_NOT_CONFIGURED","Chave Stone nao configurada.");
        URI uri=URI.create(base+"/" + resource+"?page="+page+"&size="+size);
        String basic=Base64.getEncoder().encodeToString(
                (secretKey+":").getBytes(StandardCharsets.UTF_8));
        Map<?,?> body;
        try {
            body=http.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION,"Basic "+basic)
                    .header(HttpHeaders.ACCEPT,"application/json")
                    .retrieve()
                    .onStatus(status->status.is3xxRedirection(),(request,response)->{
                        throw new StoneChargeException(HttpStatus.BAD_GATEWAY,
                                "STONE_REDIRECT_REJECTED","Redirecionamento rejeitado.");
                    })
                    .body(Map.class);
        } catch(RestClientResponseException e) {
            int status=e.getStatusCode().value();
            String code=status==401||status==403 ? "STONE_CREDENTIALS_REJECTED"
                    : status==404 ? "STONE_COLLECTION_UNAVAILABLE"
                    : status==429 ? "STONE_RATE_LIMIT"
                    : "STONE_UPSTREAM_ERROR";
            throw new StoneChargeException(status==429 ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY,code,"Consulta Stone falhou.",status);
        } catch(RestClientException|IllegalArgumentException e) {
            throw new StoneChargeException(HttpStatus.SERVICE_UNAVAILABLE,
                    "STONE_UNAVAILABLE","Consulta Stone indisponivel.");
        }
        if(body==null || !(body.get("data") instanceof List<?>))
            throw invalid();
        return body;
    }

    private static List<Map<?,?>> data(Map<?,?> body) {
        if(!(body.get("data") instanceof List<?> raw) || raw.size()>50)
            throw invalid();
        var result=new ArrayList<Map<?,?>>();
        for(Object row:raw) {
            if(!(row instanceof Map<?,?> map)) throw invalid();
            result.add(map);
        }
        return result;
    }

    private static <T> Page<T> page(Map<?,?> response,List<T> records) {
        Object rawPaging=response.get("paging");
        if(!(rawPaging instanceof Map<?,?> paging))
            return new Page<>(List.copyOf(records),null,false,false);
        Long total=null;
        Object rawTotal=paging.get("total");
        if(rawTotal!=null) {
            if(!(rawTotal instanceof Number number)
                    || !(number instanceof Integer || number instanceof Long)
                    || number.longValue()<0)
                throw invalid();
            total=number.longValue();
        }
        Object next=paging.get("next");
        if(next!=null && !(next instanceof String)) throw invalid();
        boolean hasNext=next instanceof String value && !value.isBlank();
        // Never follow the untrusted next URL: only request page+1 ourselves.
        return new Page<>(List.copyOf(records),total,hasNext,true);
    }

    private static String identifier(Object raw,String prefix) {
        if(!(raw instanceof String value) ||
                !value.matches(prefix+"[A-Za-z0-9]{4,64}")) throw invalid();
        return value;
    }

    private static String optionalOrderId(Map<?,?> row) {
        Object nestedOrder=row.get("order");
        Object nestedId=nestedOrder instanceof Map<?,?> map ? map.get("id"):null;
        Object rawId=row.get("order_id");
        String fromRoot=rawId==null ? null:identifier(rawId,"or_");
        String fromNested=nestedId==null?null:identifier(nestedId,"or_");
        if(fromRoot!=null && fromNested!=null && !fromRoot.equals(fromNested))
            throw invalid();
        return fromRoot!=null?fromRoot:fromNested;
    }

    private static String safeEnum(Object raw,Set<String> known) {
        if(!(raw instanceof String value)||value.length()>40) return "UNKNOWN";
        String candidate=value.toLowerCase(Locale.ROOT);
        return known.contains(candidate)?candidate.toUpperCase(Locale.ROOT):"UNKNOWN";
    }

    private static StoneChargeException invalid() {
        return new StoneChargeException(HttpStatus.BAD_GATEWAY,
                "STONE_INVALID_COLLECTION_RESPONSE",
                "Resposta de listagem Stone inconsistente.");
    }
}
