package com.tbridge.payments.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tbridge.common.exception.ApiException;
import com.tbridge.payments.dto.gateway.WebpayCommitResponse;
import com.tbridge.payments.dto.gateway.WebpayCreateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lo que Webpay espera recibir y lo que responde, con un servidor HTTP local en
 * vez de Transbank: las pruebas no salen a internet.
 */
class WebpayClientTest {

    private record Llamada(String metodo, String ruta, Map<String, List<String>> encabezados, String cuerpo) {}

    private HttpServer transbank;
    private final List<Llamada> llamadas = new ArrayList<>();
    private int codigo = 200;
    private String respuestaConfirmar = """
            {"vci":"TSY","amount":410000,"status":"AUTHORIZED","buy_order":"ORD41T123","session_id":"deuda-3",
             "card_detail":{"card_number":"6623"},"authorization_code":"1213","payment_type_code":"VD",
             "response_code":0,"installments_number":0,"campo_nuevo":"Transbank puede sumar campos"}""";

    @BeforeEach
    void levantar() throws IOException {
        transbank = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        transbank.createContext("/", intercambio -> {
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            llamadas.add(new Llamada(intercambio.getRequestMethod(), intercambio.getRequestURI().getPath(),
                    intercambio.getRequestHeaders(), cuerpo));
            String respuesta = "POST".equals(intercambio.getRequestMethod())
                    ? "{\"token\":\"tok123\",\"url\":\"https://webpay3gint.transbank.cl/webpayserver/initTransaction\"}"
                    : respuestaConfirmar;
            byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigo, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        transbank.start();
    }

    @AfterEach
    void bajar() {
        transbank.stop(0);
    }

    private WebpayClient cliente() {
        return new WebpayClient("http://127.0.0.1:" + transbank.getAddress().getPort(),
                "597055555532", "llave-de-prueba", "TEST");
    }

    @Test
    void crear_manda_las_credenciales_y_el_cuerpo_que_pide_transbank() throws Exception {
        WebpayCreateResponse t = cliente().createTransaction("ORD41T123", "deuda-3", 410000L, "http://localhost:8080/vuelta");

        assertEquals("tok123", t.token());
        Llamada llamada = llamadas.getFirst();
        assertEquals("POST", llamada.metodo());
        assertEquals("/rswebpaytransaction/api/webpay/v1.2/transactions", llamada.ruta());
        assertEquals("597055555532", llamada.encabezados().get("Tbk-api-key-id").getFirst());
        assertEquals("llave-de-prueba", llamada.encabezados().get("Tbk-api-key-secret").getFirst());
        JsonNode cuerpo = new ObjectMapper().readTree(llamada.cuerpo());
        assertEquals("ORD41T123", cuerpo.get("buy_order").asText());
        assertEquals("deuda-3", cuerpo.get("session_id").asText());
        assertEquals(410000L, cuerpo.get("amount").asLong());
        assertEquals("http://localhost:8080/vuelta", cuerpo.get("return_url").asText());
    }

    @Test
    void confirmar_es_un_put_con_el_token_y_aprueba_solo_con_codigo_cero() {
        WebpayCommitResponse aprobada = cliente().commitTransaction("tok123");

        assertEquals("PUT", llamadas.getFirst().metodo());
        assertEquals("/rswebpaytransaction/api/webpay/v1.2/transactions/tok123", llamadas.getFirst().ruta());
        assertTrue(aprobada.isAuthorized());
        assertEquals(410000L, aprobada.amount());
        assertEquals("6623", aprobada.cardDetail().cardNumber());

        respuestaConfirmar = "{\"status\":\"FAILED\",\"response_code\":-1,\"amount\":410000}";
        assertFalse(cliente().commitTransaction("tok123").isAuthorized());

        respuestaConfirmar = "{\"status\":\"AUTHORIZED\",\"amount\":410000}";
        assertFalse(cliente().commitTransaction("tok123").isAuthorized(), "sin codigo de respuesta no esta aprobada");
    }

    @Test
    void si_transbank_rechaza_o_no_responde_el_deudor_lee_que_pruebe_de_nuevo() {
        codigo = 422;
        ApiException rechazo = assertThrows(ApiException.class,
                () -> cliente().createTransaction("ORD41T123", "deuda-3", 410000L, "http://localhost:8080/vuelta"));
        assertEquals(HttpStatus.BAD_GATEWAY, rechazo.getStatus());
        assertTrue(rechazo.getMessage().contains("Webpay no responde"));
        assertFalse(rechazo.getMessage().contains("422"), "el detalle de Transbank no llega a la persona");

        transbank.stop(0);
        ApiException caido = assertThrows(ApiException.class, () -> cliente().commitTransaction("tok123"));
        assertEquals(HttpStatus.BAD_GATEWAY, caido.getStatus());
    }

    @Test
    void en_modo_simulada_no_cobra_de_verdad() {
        assertFalse(new WebpayClient("http://webpay.invalid", "x", "y", "SIMULADA").real());
        assertFalse(new WebpayClient("http://webpay.invalid", "x", "y", "simulada").real());
        assertTrue(cliente().real());
        assertEquals("http://127.0.0.1:" + transbank.getAddress().getPort() + "/webpayserver/initTransaction",
                cliente().paginaDePago());
    }
}
