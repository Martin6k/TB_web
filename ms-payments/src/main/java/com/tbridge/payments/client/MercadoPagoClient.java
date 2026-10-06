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
import java.util.Locale;

/**
 * Cliente REST para la API de Mercado Pago (Checkout Pro y Pagos v1).
 *
 * <pre>
 *   POST /checkout/preferences          crea la preferencia de cobro  -> {id, init_point, sandbox_init_point}
 *   GET  /checkout/preferences/{id}     lee la preferencia           -> {payments: [{id, status}], ...}
 *   GET  /v1/payments/{id}              obtiene el estado del pago   -> {status, status_detail, amount, ...}
 *   Authorization: Bearer <TOKEN> credencial de la aplicacion
 * </pre>
 *
 * <p>Dos cosas que hace distinto Mercado Pago y hay que respetar:</p>
 * <ul>
 *   <li><b>Las back_urls de http:// se descartan.</b> Mercado Pago las borra
 *       sin avisar si no son https, y si se pidio {@code auto_return} la
 *       preferencia entera se rechaza con {@code 400 invalid_auto_return}
 *       ("auto_return invalid. back_url.success must be defined"). Por eso
 *       {@code auto_return} solo se manda cuando la vuelta es https.</li>
 *   <li><b>La preferencia es la que sabe del pago.</b> Con el id de la
 *       preferencia se puede consultar que pagos se hicieron sobre ella, sin
 *       depender de que el deudor vuelva al portal ni de que el aviso llegue.</li>
 * </ul>
 */
@Component
public class MercadoPagoClient {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoClient.class);
    private static final String PREFERENCIAS = "/checkout/preferences";
    private static final String PAGOS = "/v1/payments";

    /** La preferencia de pago generada en Mercado Pago. */
    public record Preferencia(String id, String initPoint, String sandboxInitPoint) {
        public String url(boolean testMode) {
            // Mercado Pago unificó el checkout en init_point; el subdominio sandbox está discontinuado
            // y muestra "Algo anda mal". Las credenciales de prueba activan el modo test automáticamente.
            return initPoint != null && !initPoint.isBlank() ? initPoint : sandboxInitPoint;
        }
    }

    /** Estado de un pago consultado directamente a la API de Mercado Pago. */
    public record PagoInfo(Long id, String status, String statusDetail, BigDecimal transactionAmount,
                           String currencyId, String externalReference, JsonNode crudo) {
        public boolean pagado() {
            return "approved".equalsIgnoreCase(status);
        }
    }

    /**
     * Un pago hecho sobre una preferencia, tal como lo reporta Mercado Pago.
     * {@code id} y {@code status} vienen null si todavia no se pago nada.
     */
    public record PagoDePreferencia(Long id, String status, JsonNode crudo) {
        public boolean pagado() {
            return id != null && "approved".equalsIgnoreCase(status);
        }
    }

    /** Lo que se sabe de la preferencia mas adelante, para conciliar el pago. */
    public record EstadoPreferencia(String id, String externalReference, Long totalAmount,
                                    PagoDePreferencia pago) {}

    /**
     * Si Mercado Pago se va a quedar con esta direccion de vuelta.
     *
     * <p>Las back_urls que no son https las descarta en silencio (las deja
     * vacias), asi que en local, con {@code http://localhost:5173}, la vuelta
     * no existe para Mercado Pago aunque DataBridge la sirva bien.</p>
     */
    static boolean vueltaQueMercadoPagoAcepta(String url) {
        return url != null && url.trim().toLowerCase(Locale.ROOT).startsWith("https://");
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
     *
     * @param returnUrl donde vuelve el deudor. Si no es https, Mercado Pago la
     *                  descarta y el deudor no vuelve solo: el pago se concilia
     *                  contra la preferencia ({@link #consultarPreferencia}).
     */
    public Preferencia crearPreferencia(String externalReference, String titulo, long montoClp,
                                       String emailPayer, String returnUrl) {
        try {
            log.info("Creando preferencia Mercado Pago: ref={}, monto={}", externalReference, montoClp);

            Item item = new Item("item-" + externalReference, titulo, "Pago de cuotas / deuda", 1, montoClp, "CLP");
            Payer payer = new Payer(emailPayer != null && !emailPayer.isBlank() ? emailPayer : "test_user_660778198@testuser.com");
            BackUrls backUrls = new BackUrls(returnUrl, returnUrl, returnUrl);

            //  auto_return solo si la vuelta es https: Mercado Pago valida la
            //  back_url.success DESPUES de borrar las que no son https, y si la
            //  encontro vacia rechaza la preferencia entera con 400.
            boolean vuelveSiMismo = vueltaQueMercadoPagoAcepta(returnUrl);
            if (!vuelveSiMismo) {
                log.warn("La vuelta de Mercado Pago (PUBLIC_URL) no es https: Mercado Pago la va a descartar, "
                        + "el deudor no volvera solo y no se pedira auto_return. El pago se concilia por la "
                        + "preferencia. PUBLIC_URL=https://... lo arregla.");
            }

            CrearPreferencia payload = new CrearPreferencia(
                    List.of(item),
                    payer,
                    backUrls,
                    vuelveSiMismo ? "approved" : null,
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

            if (vuelveSiMismo && r.path("back_urls").path("success").asText("").isBlank()) {
                log.warn("Mercado Pago creo la preferencia {} sin back_urls.success: el deudor no va a "
                        + "volver solo. Revisa que PUBLIC_URL sea una direccion https.", id);
            }

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
     * Lee la preferencia para saber si ya se le pago y con que id.
     *
     * <p>Es la forma de conciliar un pago de Mercado Pago sin depender de la
     * vuelta del deudor ni del aviso: en local Mercado Pago no puede devolverse
     * a un {@code http://localhost} ni avisar a la maquina del desarrollador.</p>
     *
     * @return el estado de la preferencia, o null si ya no existe en Mercado Pago.
     */
    public EstadoPreferencia consultarPreferencia(String preferenceId) {
        try {
            JsonNode r = rest.get()
                    .uri(PREFERENCIAS + "/{id}", preferenceId)
                    .retrieve()
                    .body(JsonNode.class);

            if (r == null || !r.hasNonNull("id")) {
                return null;
            }

            String id = r.get("id").asText();
            String extRef = r.hasNonNull("external_reference") ? r.get("external_reference").asText() : null;
            Long total = r.hasNonNull("total_amount") ? r.get("total_amount").asLong() : null;

            //  payments[] viene vacio mientras nadie haya pagado la preferencia.
            PagoDePreferencia pago = null;
            JsonNode pagos = r.get("payments");
            if (pagos != null && pagos.isArray() && !pagos.isEmpty()) {
                JsonNode primero = pagos.get(0);
                if (primero != null && primero.hasNonNull("id")) {
                    pago = new PagoDePreferencia(primero.get("id").asLong(),
                            primero.hasNonNull("status") ? primero.get("status").asText() : null,
                            primero);
                }
            }

            log.info("Preferencia Mercado Pago {} leida: extRef={}, total={}, pago={}",
                    id, extRef, total, pago != null ? pago.id() + "/" + pago.status() : "ninguno");
            return new EstadoPreferencia(id, extRef, total, pago);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                log.warn("La preferencia {} no existe en Mercado Pago", preferenceId);
                return null;
            }
            log.error("Mercado Pago no pudo leer la preferencia {} ({}): {}",
                    preferenceId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "No se pudo consultar la preferencia en Mercado Pago");
        } catch (RestClientException e) {
            log.error("Error al consultar la preferencia {} en Mercado Pago: {}", preferenceId, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "No se pudo consultar la preferencia en Mercado Pago");
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
