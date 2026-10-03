package com.tbridge.payments.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tbridge.common.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lo que Khipu espera recibir y lo que responde, con un servidor HTTP local en
 * vez de Khipu: las pruebas no salen a internet.
 */
class KhipuClientTest {

    private record Llamada(String metodo, String ruta, Map<String, List<String>> encabezados, String cuerpo) {}

    private HttpServer servidor;
    private final List<Llamada> llamadas = new ArrayList<>();
    private int codigo = 200;
    private String respuestaEstado = """
            {"payment_id":"gqzdy6chjne9","status":"done","status_detail":"normal","amount":"410000.0000",
             "currency":"CLP","transaction_id":"TB-41","payment_method":"simplified_transfer"}""";

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            llamadas.add(new Llamada(intercambio.getRequestMethod(), intercambio.getRequestURI().getPath(),
                    intercambio.getRequestHeaders(), cuerpo));
            String respuesta = "POST".equals(intercambio.getRequestMethod())
                    ? """
                      {"payment_id":"gqzdy6chjne9","payment_url":"https://khipu.com/payment/info/gqzdy6chjne9",
                       "simplified_transfer_url":"https://app.khipu.com/payment/simplified/gqzdy6chjne9"}"""
                    : respuestaEstado;
            byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigo, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void bajar() {
        servidor.stop(0);
    }

    private KhipuClient cliente() {
        return new KhipuClient("http://127.0.0.1:" + servidor.getAddress().getPort(), "llave-de-prueba");
    }

    @Test
    void crear_manda_la_llave_y_el_cuerpo_que_pide_khipu() throws Exception {
        KhipuClient.Cobro cobro = cliente().crear("TB-41", "Pago de deuda", 410000L, "http://localhost:8080/vuelta",
                "http://localhost:8080/vuelta&cancelado=1", null, OffsetDateTime.parse("2026-10-03T12:30:00.123456789-03:00"));

        assertEquals("gqzdy6chjne9", cobro.paymentId());
        assertEquals("https://khipu.com/payment/info/gqzdy6chjne9", cobro.paymentUrl());
        Llamada llamada = llamadas.getFirst();
        assertEquals("POST", llamada.metodo());
        assertEquals("/v3/payments", llamada.ruta());
        assertEquals("llave-de-prueba", llamada.encabezados().get("X-api-key").getFirst());
        JsonNode cuerpo = new ObjectMapper().readTree(llamada.cuerpo());
        assertEquals(410000, cuerpo.get("amount").asLong());
        assertEquals("CLP", cuerpo.get("currency").asText());
        assertEquals("TB-41", cuerpo.get("transaction_id").asText());
        assertEquals("http://localhost:8080/vuelta", cuerpo.get("return_url").asText());
        assertEquals("http://localhost:8080/vuelta&cancelado=1", cuerpo.get("cancel_url").asText());
        assertEquals("2026-10-03T12:30:00-03:00", cuerpo.get("expires_date").asText(), "con segundos y sin fracciones");
        assertFalse(cuerpo.has("notify_url"), "sin direccion publica no se pide aviso");
        assertFalse(cuerpo.has("notify_api_version"));
    }

    @Test
    void con_direccion_publica_pide_el_aviso_en_la_version_3() throws Exception {
        cliente().crear("TB-41", "Pago de deuda", 1000L, "http://x/v", "http://x/c",
                "https://databridge.cl/api/payments/public/khipu/aviso", null);

        JsonNode cuerpo = new ObjectMapper().readTree(llamadas.getFirst().cuerpo());
        assertEquals("https://databridge.cl/api/payments/public/khipu/aviso", cuerpo.get("notify_url").asText());
        assertEquals("3.0", cuerpo.get("notify_api_version").asText());
    }

    @Test
    void estado_lee_lo_que_dice_khipu_del_pago() {
        KhipuClient.Estado estado = cliente().estado("gqzdy6chjne9");

        assertEquals("/v3/payments/gqzdy6chjne9", llamadas.getFirst().ruta());
        assertEquals("GET", llamadas.getFirst().metodo());
        assertTrue(estado.pagado());
        assertEquals(0, estado.amount().compareTo(new BigDecimal("410000")));
        assertEquals("TB-41", estado.transactionId());
        assertTrue(estado.crudo().toString().contains("simplified_transfer"));
    }

    @Test
    void que_cuenta_como_pagado_y_que_como_sin_cobro() {
        respuestaEstado = "{\"status\":\"verifying\",\"status_detail\":\"pending\",\"amount\":\"1\"}";
        KhipuClient.Estado verificando = cliente().estado("x");
        assertFalse(verificando.pagado());
        assertFalse(verificando.sinCobro());

        respuestaEstado = "{\"status\":\"done\",\"status_detail\":\"reversed\",\"amount\":\"1\"}";
        KhipuClient.Estado revertido = cliente().estado("x");
        assertFalse(revertido.pagado());
        assertTrue(revertido.sinCobro());
    }

    @Test
    void si_khipu_responde_con_error_es_502_con_un_mensaje_para_la_persona() {
        codigo = 401;
        ApiException error = assertThrows(ApiException.class,
                () -> cliente().crear("TB-41", "x", 1L, "http://x/v", "http://x/c", null, null));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
        assertTrue(error.getMessage().contains("Khipu no responde"));

        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ApiException.class, () -> cliente().estado("x")).getStatus());
    }

    @Test
    void sin_llave_khipu_no_es_real() {
        assertFalse(new KhipuClient("https://payment-api.khipu.com", "").real());
        assertFalse(new KhipuClient("https://payment-api.khipu.com", "  ").real());
        assertTrue(cliente().real());
    }
}
