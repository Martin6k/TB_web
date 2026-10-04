package com.tbridge.payments.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tbridge.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Khipu, por su API de pagos v3.
 *
 * <pre>
 *   POST /v3/payments        crea el cobro  -> {payment_id, payment_url, ...}
 *   GET  /v3/payments/{id}   en que va      -> {status, status_detail, amount, transaction_id, ...}
 *   x-api-key                la llave de la cuenta de cobro
 * </pre>
 *
 * <p>El deudor paga en la pagina de Khipu, con una transferencia desde su
 * banco. Khipu lo devuelve a la {@code return_url}, o a la {@code cancel_url}
 * si se arrepiente, sin decir nada del pago: lo que paso se le pregunta con
 * {@code GET /v3/payments/{id}}. Esta hecho solo si {@code status} es
 * {@code done} y el detalle no dice que se revirtio o se devolvio.
 *
 * <p>Con una cuenta de cobro en <b>modo desarrollador</b> los bancos y la plata
 * son de mentira (se paga con DemoBank), y la API es la misma. Sin
 * {@code KHIPU_LLAVE}, Khipu queda simulada.
 */
@Component
public class KhipuClient {

    private static final String PAGOS = "/v3/payments";

    /** ISO-8601 con los segundos siempre y sin fracciones: 2026-10-04T00:40:18-03:00. */
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** Lo que Khipu dice de un pago terminado que no se cobro. */
    private static final Set<String> SIN_COBRO = Set.of(
            "rejected-by-payer", "marked-as-abuse", "reversed", "partially-refunded", "fully-refunded");

    /** El cobro recien creado: su id en Khipu y a donde mandar al deudor. */
    public record Cobro(String paymentId, String paymentUrl) {}

    /** En que va un pago. {@code crudo} se guarda tal cual, para conciliar. */
    public record Estado(String status, String statusDetail, BigDecimal amount, String currency,
                         String transactionId, JsonNode crudo) {

        /** Pagado de verdad: Khipu lo concilio y nadie lo revirtio. */
        public boolean pagado() {
            return "done".equals(status) && !SIN_COBRO.contains(statusDetail);
        }

        /** Terminado sin plata: el deudor lo rechazo, o se revirtio o devolvio. */
        public boolean sinCobro() {
            return SIN_COBRO.contains(statusDetail);
        }
    }

    private final boolean real;
    private final RestClient rest;

    public KhipuClient(@Value("${app.khipu.url:https://payment-api.khipu.com}") String url,
                       @Value("${app.khipu.llave:}") String llave) {
        this.real = llave != null && !llave.isBlank();
        this.rest = RestClient.builder()
                .baseUrl(url.replaceAll("/$", ""))
                .defaultHeader("x-api-key", llave == null ? "" : llave.trim())
                .build();
    }

    /** Si Khipu cobra de verdad (hay llave) o queda la pasarela simulada. */
    public boolean real() {
        return real;
    }

    /**
     * Abre el cobro en Khipu. Se cobra en pesos enteros: una deuda en UF ya
     * llega convertida, con la UF del dia en que se abrio.
     *
     * @param avisos a donde Khipu avisa cuando el pago se concilia; {@code null}
     *               si DataBridge no tiene una direccion publica (en local)
     */
    public Cobro crear(String transaccion, String asunto, long montoClp, String retorno, String cancelado,
                       String avisos, OffsetDateTime vence) {
        try {
            JsonNode r = rest.post().uri(PAGOS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Crear(montoClp, "CLP", asunto, transaccion, retorno, cancelado, avisos,
                            avisos == null ? null : "3.0",
                            vence == null ? null : vence.truncatedTo(ChronoUnit.SECONDS).format(FECHA)))
                    .retrieve()
                    .body(JsonNode.class);
            if (r == null || !r.hasNonNull("payment_id") || !r.hasNonNull("payment_url")) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Khipu no devolvio el cobro");
            }
            return new Cobro(r.get("payment_id").asText(), r.get("payment_url").asText());
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Khipu no responde en este momento. Prueba de nuevo, o paga con otro medio.");
        }
    }

    /** En que va el pago, segun Khipu. */
    public Estado estado(String paymentId) {
        try {
            JsonNode r = rest.get().uri(PAGOS + "/{id}", paymentId).retrieve().body(JsonNode.class);
            if (r == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Khipu no dijo en que va el pago");
            }
            BigDecimal monto = r.hasNonNull("amount") ? new BigDecimal(r.get("amount").asText()) : null;
            return new Estado(texto(r, "status"), texto(r, "status_detail"), monto, texto(r, "currency"),
                    texto(r, "transaction_id"), r);
        } catch (RestClientException | NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Khipu no dijo en que va el pago");
        }
    }

    private static String texto(JsonNode nodo, String campo) {
        return nodo.hasNonNull(campo) ? nodo.get(campo).asText() : null;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Crear(long amount, String currency, String subject, String transactionId, String returnUrl,
                         String cancelUrl, String notifyUrl, String notifyApiVersion, String expiresDate) {}
}
