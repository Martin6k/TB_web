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

/**
 * Cliente REST para la API de Transbank Webpay Plus (Integración / TEST).
 */
@Component
public class WebpayClient {

    private static final Logger log = LoggerFactory.getLogger(WebpayClient.class);

    private final RestClient rest;
    private final String commerceCode;
    private final String apiKey;

    public WebpayClient(
            @Value("${app.transbank.url:https://webpay3gint.transbank.cl}") String apiUrl,
            @Value("${app.transbank.commerce-code:597055555532}") String commerceCode,
            @Value("${app.transbank.api-key:579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C}") String apiKey
    ) {
        this.rest = RestClient.builder().baseUrl(apiUrl.replaceAll("/$", "")).build();
        this.commerceCode = commerceCode;
        this.apiKey = apiKey;
    }

    /**
     * Inicia una transacción en Webpay Plus.
     */
    public WebpayCreateResponse createTransaction(String buyOrder, String sessionId, long amount, String returnUrl) {
        try {
            log.info("Iniciando transacción Webpay: buyOrder={}, sessionId={}, amount={}", buyOrder, sessionId, amount);
            WebpayCreateResponse response = rest.post()
                    .uri("/rswebpaytransaction/api/webpay/v1.0/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Tbk-Api-Key-Id", commerceCode)
                    .header("Tbk-Api-Key-Secret", apiKey)
                    .body(new WebpayCreateRequest(buyOrder, sessionId, amount, returnUrl))
                    .retrieve()
                    .body(WebpayCreateResponse.class);

            if (response == null || response.token() == null || response.url() == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Respuesta incompleta de Transbank Webpay");
            }
            log.info("Transacción Webpay creada exitosamente con token={}", response.token());
            return response;
        } catch (HttpClientErrorException e) {
            log.error("Error cliente Transbank Webpay ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de comunicación con Transbank Webpay: " + e.getMessage());
        } catch (RestClientException e) {
            log.error("Error al conectar con Transbank Webpay", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No fue posible conectar con Transbank Webpay");
        }
    }

    /**
     * Confirma / comitea una transacción en Webpay Plus usando el token devuelto por Transbank.
     */
    public WebpayCommitResponse commitTransaction(String token) {
        try {
            log.info("Confirmando transacción Webpay con token={}", token);
            WebpayCommitResponse response = rest.put()
                    .uri("/rswebpaytransaction/api/webpay/v1.0/transactions/{token}", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Tbk-Api-Key-Id", commerceCode)
                    .header("Tbk-Api-Key-Secret", apiKey)
                    .retrieve()
                    .body(WebpayCommitResponse.class);

            if (response == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Respuesta vacía de Transbank Webpay al confirmar");
            }
            log.info("Resultado confirmación Webpay: status={}, responseCode={}, authCode={}",
                    response.status(), response.responseCode(), response.authorizationCode());
            return response;
        } catch (HttpClientErrorException e) {
            log.error("Error cliente Transbank al confirmar ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_REQUEST, "Error al confirmar transacción en Transbank Webpay");
        } catch (RestClientException e) {
            log.error("Error al conectar con Transbank Webpay durante commit", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No fue posible confirmar el pago con Transbank");
        }
    }
}
