package com.tbridge.payments.client;

import com.tbridge.common.exception.ApiException;
import com.tbridge.payments.dto.gateway.WebpayCommitResponse;
import com.tbridge.payments.dto.gateway.WebpayCreateRequest;
import com.tbridge.payments.dto.gateway.WebpayCreateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Locale;

/**
 * Cliente REST para la API de Transbank Webpay Plus (v1.2).
 *
 * <pre>
 *   POST /rswebpaytransaction/api/webpay/v1.2/transactions          crea la transaccion -> {token, url}
 *   PUT  /rswebpaytransaction/api/webpay/v1.2/transactions/{token}  la confirma         -> {status, response_code, ...}
 *   Tbk-Api-Key-Id / Tbk-Api-Key-Secret                              el codigo de comercio y su llave
 * </pre>
 *
 * <p>Entre las dos llamadas el deudor paga en la pagina de Webpay: se le manda
 * ahi con un formulario POST que lleva el {@code token_ws}, y Webpay lo devuelve
 * a la {@code return_url}. Un pago esta aprobado solo si la confirmacion
 * responde {@code status} AUTHORIZED y {@code response_code} 0.
 *
 * <p>Por omision usa el ambiente de integracion (TEST), con el codigo de
 * comercio y la llave que Transbank publica para que cualquiera pruebe sin
 * registrarse. Con {@code TRANSBANK_ENVIRONMENT=SIMULADA}, Webpay vuelve a la
 * pasarela simulada, sin internet.
 */
@Component
public class WebpayClient {

    private static final Logger log = LoggerFactory.getLogger(WebpayClient.class);
    private static final String TRANSACCIONES = "/rswebpaytransaction/api/webpay/v1.2/transactions";

    private final RestClient rest;
    private final String base;
    private final String commerceCode;
    private final String apiKey;
    private final boolean real;

    public WebpayClient(
            @Value("${app.transbank.url:https://webpay3gint.transbank.cl}") String apiUrl,
            @Value("${app.transbank.commerce-code:597055555532}") String commerceCode,
            @Value("${app.transbank.api-key:579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C}") String apiKey,
            @Value("${app.transbank.environment:TEST}") String environment
    ) {
        this.base = apiUrl.replaceAll("/$", "");
        this.rest = RestClient.builder().baseUrl(base).build();
        this.commerceCode = commerceCode;
        this.apiKey = apiKey;
        this.real = !"SIMULADA".equals(environment.trim().toUpperCase(Locale.ROOT));
    }

    /** Si Webpay cobra de verdad (TEST o produccion) o queda la pasarela simulada. */
    public boolean real() {
        return real;
    }

    /** La pagina de Webpay a la que se lleva al deudor con el token, por POST. */
    public String paginaDePago() {
        return base + "/webpayserver/initTransaction";
    }

    /**
     * Inicia una transaccion en Webpay Plus. Cobra en pesos enteros: una deuda
     * en UF ya llega convertida, con la UF del dia en que se abrio el cobro.
     */
    public WebpayCreateResponse createTransaction(String buyOrder, String sessionId, long amount, String returnUrl) {
        try {
            log.info("Iniciando transaccion Webpay: buyOrder={}, amount={}", buyOrder, amount);
            WebpayCreateResponse response = rest.post()
                    .uri(TRANSACCIONES)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Tbk-Api-Key-Id", commerceCode)
                    .header("Tbk-Api-Key-Secret", apiKey)
                    .body(new WebpayCreateRequest(buyOrder, sessionId, amount, returnUrl))
                    .retrieve()
                    .body(WebpayCreateResponse.class);

            if (response == null || response.token() == null || response.url() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no devolvio la transaccion");
            }
            return response;
        } catch (HttpClientErrorException e) {
            log.error("Transbank rechazo la transaccion {} ({}): {}", buyOrder, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Webpay no responde en este momento. Prueba de nuevo, o paga con otro medio.");
        } catch (RestClientException e) {
            log.error("No fue posible conectar con Transbank Webpay", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Webpay no responde en este momento. Prueba de nuevo, o paga con otro medio.");
        }
    }

    /**
     * Confirma la transaccion: es lo que la cierra en Transbank. Sin esta
     * llamada el cargo no queda hecho, aunque el deudor haya visto "aprobado".
     */
    public WebpayCommitResponse commitTransaction(String token) {
        try {
            WebpayCommitResponse response = rest.put()
                    .uri(TRANSACCIONES + "/{token}", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Tbk-Api-Key-Id", commerceCode)
                    .header("Tbk-Api-Key-Secret", apiKey)
                    .retrieve()
                    .body(WebpayCommitResponse.class);

            if (response == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no confirmo la transaccion");
            }
            log.info("Confirmacion Webpay: buyOrder={}, status={}, responseCode={}",
                    response.buyOrder(), response.status(), response.responseCode());
            return response;
        } catch (HttpClientErrorException e) {
            log.error("Transbank rechazo la confirmacion ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no confirmo la transaccion");
        } catch (RestClientException e) {
            log.error("No fue posible confirmar con Transbank Webpay", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no confirmo la transaccion");
        }
    }
}
