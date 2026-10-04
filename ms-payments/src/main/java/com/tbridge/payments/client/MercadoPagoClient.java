package com.tbridge.payments.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tbridge.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cliente REST para la API de Mercado Pago (Checkout Pro y Pagos v1).
 *
 * <pre>
 *   POST /checkout/preferences    crea la preferencia de cobro -> {id, init_point, sandbox_init_point}
 *   GET  /v1/payments/{id}        obtiene el estado del pago    -> {status, status_detail, amount, ...}
 *   Authorization: Bearer <TOKEN> credencial de la aplicacion
 * </pre>
 */
@Component
public class MercadoPagoClient {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoClient.class);
    private static final String PREFERENCIAS = "/checkout/preferences";
    private static final String PAGOS = "/v1/payments";

    /** La preferencia de pago generada en Mercado Pago. */
    public record Preferencia(String id, String initPoint, String sandboxInitPoint) {
        public String url(boolean testMode) {
            if (testMode && sandboxInitPoint != null && !sandboxInitPoint.isBlank()) {
                return sandboxInitPoint;
            }
            return initPoint != null ? initPoint : sandboxInitPoint;
        }
    }

    /** Estado de un pago consultado directamente a la API de Mercado Pago. */
    public record PagoInfo(Long id, String status, String statusDetail, BigDecimal transactionAmount,
                           String currencyId, String externalReference, JsonNode crudo) {
        public boolean pagado() {
            return "approved".equalsIgnoreCase(status);
        }
    }

    private final RestClient rest;
    private final String accessToken;
    private final String publicKey;
    private final boolean real;
    private final boolean testMode;

    public MercadoPagoClient(
            @Value("${app.mercadopago.url:https://api.mercadopago.com}") String apiUrl,
            @Value("${app.mercadopago.access-token:}") String accessToken,
            @Value("${app.mercadopago.public-key:}") String publicKey,
            @Value("${app.mercadopago.environment:TEST}") String environment
    ) {
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.real = !this.accessToken.isBlank() && !"SIMULADA".equalsIgnoreCase(environment == null ? "" : environment.trim());
        this.testMode = "TEST".equalsIgnoreCase(environment == null ? "" : environment.trim())
                || "SANDBOX".equalsIgnoreCase(environment == null ? "" : environment.trim());
        this.rest = RestClient.builder()
                .baseUrl(apiUrl.replaceAll("/$", ""))
                .defaultHeader("Authorization", "Bearer " + this.accessToken)
                .build();
    }

    /** Si Mercado Pago cobra de verdad (hay access token configurado) o se usa simulacion. */
    public boolean real() {
        return real;
    }

    public boolean testMode() {
        return testMode;
    }

    public String getPublicKey() {
        return publicKey;
    }

    /**
     * Crea una preferencia de pago en Mercado Pago Checkout Pro.
     */
    public Preferencia crearPreferencia(String externalReference, String titulo, long montoClp,
                                       String emailPayer, String returnUrl) {
        try {
            log.info("Creando preferencia Mercado Pago: ref={}, monto={}", externalReference, montoClp);

            Item item = new Item("item-" + externalReference, titulo, "Pago de cuotas / deuda", 1, montoClp, "CLP");
            Payer payer = new Payer(emailPayer != null && !emailPayer.isBlank() ? emailPayer : "test_user_660778198@testuser.com");
            BackUrls backUrls = new BackUrls(returnUrl, returnUrl, returnUrl);

            CrearPreferencia payload = new CrearPreferencia(
                    List.of(item),
                    payer,
                    backUrls,
                    "approved",
                    externalReference,
                    "TECHNICAL BRIDGE"
            );

            JsonNode r = rest.post()
                    .uri(PREFERENCIAS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            if (r == null || !r.hasNonNull("id")) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Mercado Pago no devolvió la preferencia");
            }

            String id = r.get("id").asText();
            String initPoint = r.hasNonNull("init_point") ? r.get("init_point").asText() : null;
            String sandboxInitPoint = r.hasNonNull("sandbox_init_point") ? r.get("sandbox_init_point").asText() : null;

            log.info("Preferencia Mercado Pago creada con éxito: id={}, initPoint={}", id, initPoint);
            return new Preferencia(id, initPoint, sandboxInitPoint);
        } catch (HttpClientErrorException e) {
            log.error("Mercado Pago rechazo la preferencia ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Mercado Pago rechazó la transacción. Verifica las credenciales.");
        } catch (RestClientException e) {
            log.error("No fue posible conectar con Mercado Pago", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Mercado Pago no responde en este momento. Prueba de nuevo o con otro medio.");
        }
    }

    /**
     * Consulta el estado de un pago directamente en la API de Mercado Pago.
     */
    public PagoInfo consultarPago(String paymentId) {
        try {
            JsonNode r = rest.get()
                    .uri(PAGOS + "/{id}", paymentId)
                    .retrieve()
                    .body(JsonNode.class);

            if (r == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Mercado Pago no respondió sobre el pago");
            }

            Long id = r.hasNonNull("id") ? r.get("id").asLong() : null;
            String status = r.hasNonNull("status") ? r.get("status").asText() : null;
            String statusDetail = r.hasNonNull("status_detail") ? r.get("status_detail").asText() : null;
            BigDecimal amount = r.hasNonNull("transaction_amount") ? new BigDecimal(r.get("transaction_amount").asText()) : null;
            String currency = r.hasNonNull("currency_id") ? r.get("currency_id").asText() : null;
            String extRef = r.hasNonNull("external_reference") ? r.get("external_reference").asText() : null;

            log.info("Consulta de pago Mercado Pago {}: status={}, amount={}", paymentId, status, amount);
            return new PagoInfo(id, status, statusDetail, amount, currency, extRef, r);
        } catch (RestClientException e) {
            log.error("Error al consultar pago {} en Mercado Pago: {}", paymentId, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "No se pudo consultar el estado del pago en Mercado Pago");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CrearPreferencia(
            List<Item> items,
            Payer payer,
            BackUrls backUrls,
            String autoReturn,
            String externalReference,
            String statementDescriptor
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Item(String id, String title, String description, int quantity, long unitPrice, String currencyId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Payer(String email) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BackUrls(String success, String failure, String pending) {}
}
