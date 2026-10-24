package br.com.adpilot.integrations.stone;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
 * Fixed-host Pagar.me Core v5 read-only charge lookup.
 * Never exposes raw provider objects, customer details, card or credentials.
 * A charge lookup does not prove order association, refund or settlement.
 */
@Component
public class StoneChargeClient {
    private static final URI PRODUCTION_BASE = URI.create("https://api.pagar.me/core/v5");
    private static final Set<String> STATUSES = Set.of(
            "pending","paid","canceled","processing","failed",
            "overpaid","underpaid","chargedback");
    private static final Set<String> PAYMENT_METHODS = Set.of(
            "credit_card","debit_card","pix","boleto","voucher","bank_transfer");

    private final String secretKey;
    private final URI base;
    private final RestClient http;

    @Autowired
    public StoneChargeClient(@Value("${adpilot.stone.secret-key:}") String secretKey,
            RestClient.Builder builder) {
        this(secretKey,builder,PRODUCTION_BASE);
    }

    // Test-only injection of a loopback fake provider; production has fixed HTTPS host.
    StoneChargeClient(String secretKey,RestClient.Builder builder,URI base) {
        this.secretKey=secretKey==null ? "" : secretKey;
        this.base=base;
        var transport=HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory=new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http=builder.requestFactory(factory).build();
    }

    public boolean configured() {
        return secretKey.startsWith("sk_") && secretKey.length()>=8
                && secretKey.equals(secretKey.trim());
    }

    public record ChargeSignals(String status,String paymentMethod,String currency,
            boolean amountPresent,boolean paidAmountPresent,
            boolean amountFormatValid,boolean paidAmountFormatValid) {}

    public ChargeSignals lookup(String chargeId) {
        if (chargeId==null || !chargeId.matches("ch_[A-Za-z0-9]{4,64}"))
            throw new StoneChargeException(HttpStatus.BAD_REQUEST,"INVALID_CHARGE_ID",
                    "Identificador da cobranca invalido.");
        if (!configured())
            throw new StoneChargeException(HttpStatus.SERVICE_UNAVAILABLE,
                    "STONE_NOT_CONFIGURED", "Consulta Stone desabilitada: chave nao configurada.");

        String basic = Base64.getEncoder().encodeToString(
                (secretKey+":").getBytes(StandardCharsets.UTF_8));
        URI target=URI.create(base.toString()+"/charges/"+chargeId);
        Map<?,?> payload;
        try {
            payload=http.get().uri(target)
                    .header(HttpHeaders.AUTHORIZATION,"Basic "+basic)
                    .header(HttpHeaders.ACCEPT,"application/json")
                    .retrieve()
                    // Reject any provider redirect: never forward credentials.
                    .onStatus(status -> status.is3xxRedirection(), (request, response) -> {
                        throw new StoneChargeException(HttpStatus.BAD_GATEWAY,
                                "STONE_REDIRECT_REJECTED",
                                "Redirecionamento da API Stone rejeitado.");
                    })
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            int status=e.getStatusCode().value();
            String code=status==401 || status==403 ? "STONE_CREDENTIALS_REJECTED"
                    : status==404 ? "STONE_CHARGE_NOT_FOUND"
                    : status==429 ? "STONE_RATE_LIMIT"
                    : status>=300 && status<400 ? "STONE_REDIRECT_REJECTED"
                    : "STONE_UPSTREAM_ERROR";
            throw new StoneChargeException(status==429 ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY,code,
                    "Falha ao consultar cobranca Stone.", status);
        } catch (RestClientException | IllegalArgumentException e) {
            throw new StoneChargeException(HttpStatus.SERVICE_UNAVAILABLE,
                    "STONE_UNAVAILABLE","Consulta Stone indisponivel.");
        }

        if (payload==null || !(payload.get("id") instanceof String returnedId)
                || !chargeId.equals(returnedId))
            throw new StoneChargeException(HttpStatus.BAD_GATEWAY,
                    "STONE_INVALID_CHARGE_RESPONSE","Dados de cobranca inconsistentes.");

        Object rawAmount=payload.get("amount");
        Object rawPaid=payload.get("paid_amount");
        return new ChargeSignals(
                whitelisted(payload.get("status"),STATUSES),
                whitelisted(payload.get("payment_method"),PAYMENT_METHODS),
                "BRL".equals(payload.get("currency")) ? "BRL" : "UNKNOWN",
                rawAmount!=null,rawPaid!=null,
                validCents(rawAmount),validCents(rawPaid));
    }

    private static String whitelisted(Object raw,Set<String> allowed) {
        if (!(raw instanceof String value) || value.length()>40) return "UNKNOWN";
        String code=value.toLowerCase(Locale.ROOT);
        return allowed.contains(code) ? code.toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private static boolean validCents(Object value) {
        if (!(value instanceof Number number)) return false;
        // Provider documents amounts as integer cents; no decimal rounding.
        if (number instanceof Double || number instanceof Float) return false;
        String text=number.toString();
        if (!text.matches("(0|[1-9][0-9]{0,11})")) return false;
        try {
            return Long.parseLong(text)<=Integer.MAX_VALUE;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
