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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lo que Mercado Pago espera recibir y lo que responde, con un servidor HTTP local
 * en vez de Mercado Pago: las pruebas no salen a internet.
 *
 * <p>El servidor de mentira copia las dos rarezas que hacen que este cobro no
 * funcione si no se mandan bien las back_urls:</p>
 * <ul>
 *   <li>descarta en silencio las back_urls que no son https;</li>
 *   <li>si le pidieron {@code auto_return} y la {@code back_urls.success} quedo
 *       vacia, rechaza la preferencia entera con {@code 400 invalid_auto_return}.</li>
 * </ul>
 */
class MercadoPagoClientTest {

    private static final String TOKEN = "APP_USR-8079701943220387-100401-c35823cb3e6ca0dd24d21af811de65b4-3737969390";

    private record Llamada(String metodo, String ruta, Map<String, List<String>> encabezados, String cuerpo) {}

    private HttpServer mp;
    private final List<Llamada> llamadas = new ArrayList<>();
    private String respuestaPreferencia = """
            {"id":"3737969390-pref1","external_reference":"41","total_amount":410000,"payments":null,
             "init_point":"https://www.mercadopago.cl/checkout/v1/redirect?pref_id=3737969390-pref1",
             "sandbox_init_point":"https://sandbox.mercadopago.cl/checkout/v1/redirect?pref_id=3737969390-pref1",
             "back_urls":{"success":"https://databridge.cl/vuelta","failure":"","pending":""}}""";
    private String respuestaPago = """
            {"id":999,"status":"approved","status_detail":"accredited","transaction_amount":410000,
             "currency_id":"CLP","external_reference":"41"}""";
    private int codigo = 200;

    @BeforeEach
    void levantar() throws IOException {
        mp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mp.createContext("/", intercambio -> {
            String ruta = intercambio.getRequestURI().getPath();
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            llamadas.add(new Llamada(intercambio.getRequestMethod(), ruta,
                    intercambio.getRequestHeaders(), cuerpo));

            String respuesta;
            int estado = codigo;
            if (ruta.equals("/checkout/preferences")) {
                //  Lo que hace Mercado Pago de verdad con las back_urls.
                JsonNode enviado = new ObjectMapper().readTree(cuerpo);
                String success = enviado.path("back_urls").path("success").asText("");
                boolean guardada = success.startsWith("https://");
                if (enviado.hasNonNull("auto_return") && !guardada) {
                    estado = 400;
                    respuesta = "{\"message\":\"auto_return invalid. back_url.success must be defined\","
                            + "\"error\":\"invalid_auto_return\",\"status\":400}";
                } else {
                    //  Las que no son https quedan vacias, sin avisar.
                    String backUrls = "\"back_urls\":{\"success\":\"" + (guardada ? success : "")
                            + "\",\"failure\":\"\",\"pending\":\"\"}";
                    respuesta = respuestaPreferencia.replace(
                            "\"back_urls\":{\"success\":\"https://databridge.cl/vuelta\",\"failure\":\"\",\"pending\":\"\"}",
                            backUrls);
                }
            } else if (ruta.startsWith("/v1/payments/")) {
                respuesta = respuestaPago;
            } else if (ruta.startsWith("/checkout/preferences/")) {
                respuesta = respuestaPreferencia;
            } else {
                estado = 404;
                respuesta = "{\"message\":\"not found\",\"status\":404}";
            }

            byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        mp.start();
    }

    @AfterEach
    void bajar() {
        mp.stop(0);
    }

    private MercadoPagoClient cliente() {
        return new MercadoPagoClient("http://127.0.0.1:" + mp.getAddress().getPort(), TOKEN,
                "APP_USR-0e4b2202-9b14-4b4b-9696-28802be3048a", "TEST");
    }

    private JsonNode cuerpoDeLaPreferencia() throws Exception {
        return new ObjectMapper().readTree(llamadas.getFirst().cuerpo());
    }

    // ------------------------------------------------------------------
    //  El bug: auto_return con una vuelta que no es https
    // ------------------------------------------------------------------

    @Test
    void con_la_vuelta_en_http_no_pide_auto_return_y_crea_la_preferencia() throws Exception {
        //  Con PUBLIC_URL=http://localhost:5173, como viene por omision en local.
        MercadoPagoClient.Preferencia pref = cliente().crearPreferencia("41", "Pago de deuda N° 3",
                410000L, "felipe@correo.cl", "http://localhost:5173/api/payments/public/mercadopago/retorno");

        assertNotNull(pref.id());
        JsonNode cuerpo = cuerpoDeLaPreferencia();
        assertFalse(cuerpo.hasNonNull("auto_return"),
                "Mercado Pago rechaza la preferencia entera si auto_return no tiene una back_urls.success https");
        assertEquals("http://localhost:5173/api/payments/public/mercadopago/retorno",
                cuerpo.at("/back_urls/success").asText());
        assertEquals("41", cuerpo.get("external_reference").asText());
        assertEquals(410000L, cuerpo.at("/items/0/unit_price").asLong());
        assertEquals("CLP", cuerpo.at("/items/0/currency_id").asText());
        assertEquals("felipe@correo.cl", cuerpo.at("/payer/email").asText());
    }

    @Test
    void con_la_vuelta_en_https_pide_auto_return() throws Exception {
        cliente().crearPreferencia("41", "Pago de deuda N° 3", 410000L, "felipe@correo.cl",
                "https://databridge.cl/api/payments/public/mercadopago/retorno");

        assertEquals("approved", cuerpoDeLaPreferencia().get("auto_return").asText());
    }

    @Test
    void manda_el_bearer_del_access_token() {
        cliente().crearPreferencia("41", "Pago", 410000L, "a@b.cl", "http://localhost:5173/vuelta");

        assertEquals("Bearer " + TOKEN, llamadas.getFirst().encabezados().get("Authorization").getFirst());
    }

    @Test
    void en_prueba_abre_el_sandbox_init_point() {
        MercadoPagoClient.Preferencia pref = cliente().crearPreferencia("41", "Pago", 410000L, "a@b.cl",
                "http://localhost:5173/vuelta");

        assertTrue(pref.url(true).startsWith("https://sandbox.mercadopago.cl/"));
        assertTrue(pref.url(false).startsWith("https://www.mercadopago.cl/"));
    }

    @Test
    void si_mercado_pago_rechaza_el_deudor_lee_que_pruebe_de_nuevo() {
        codigo = 400;

        ApiException rechazo = assertThrows(ApiException.class, () -> cliente().crearPreferencia("41", "Pago",
                410000L, "a@b.cl", "http://localhost:5173/vuelta"));

        assertEquals(HttpStatus.BAD_GATEWAY, rechazo.getStatus());
        assertTrue(rechazo.getMessage().contains("credenciales"));
    }

    // ------------------------------------------------------------------
    //  Consultar el pago y la preferencia
    // ------------------------------------------------------------------

    @Test
    void consultar_el_pago_devuelve_el_estado() {
        MercadoPagoClient.PagoInfo info = cliente().consultarPago("999");

        assertEquals("GET", llamadas.getFirst().metodo());
        assertEquals("/v1/payments/999", llamadas.getFirst().ruta());
        assertEquals(999L, info.id());
        assertEquals("approved", info.status());
        assertEquals("accredited", info.statusDetail());
        assertEquals("41", info.externalReference());
        assertEquals(0, info.transactionAmount().compareTo(new java.math.BigDecimal("410000")));
        assertTrue(info.pagado());
    }

    @Test
    void la_preferencia_sin_pagos_no_confirma_nada() {
        MercadoPagoClient.EstadoPreferencia pref = cliente().consultarPreferencia("3737969390-pref1");

        assertEquals("GET", llamadas.getFirst().metodo());
        assertEquals("/checkout/preferences/3737969390-pref1", llamadas.getFirst().ruta());
        assertEquals("41", pref.externalReference());
        assertNull(pref.pago(), "sin pagos no se concilia nada");
    }

    @Test
    void la_preferencia_con_un_pago_aprobado_lo_entrega_para_conciliar() {
        respuestaPreferencia = respuestaPreferencia.replace("\"payments\":null", """
                "payments":[{"id":999,"status":"approved","status_detail":"accredited","amount":410000}]""");

        MercadoPagoClient.EstadoPreferencia pref = cliente().consultarPreferencia("3737969390-pref1");

        assertNotNull(pref.pago());
        assertEquals(999L, pref.pago().id());
        assertEquals("approved", pref.pago().status());
        assertTrue(pref.pago().pagado());
    }

    @Test
    void una_preferencia_que_no_existe_no_rompe_la_conciliacion() {
        codigo = 404;

        assertNull(cliente().consultarPreferencia("pref-que-no-existe"));
    }

    @Test
    void si_mercado_pago_cae_la_conciliacion_lo_dice_sin_tirar_la_excepcion() {
        codigo = 500;

        ApiException caido = assertThrows(ApiException.class, () -> cliente().consultarPreferencia("pref1"));

        assertEquals(HttpStatus.BAD_GATEWAY, caido.getStatus());
    }

    // ------------------------------------------------------------------
    //  Modo
    // ------------------------------------------------------------------

    @Test
    void en_modo_simulada_no_cobra_de_verdad() {
        assertFalse(new MercadoPagoClient("http://mp.invalid", "", "pk", "SIMULADA").real());
        assertFalse(new MercadoPagoClient("http://mp.invalid", TOKEN, "pk", "SIMULADA").real());
        assertFalse(new MercadoPagoClient("http://mp.invalid", "", "pk", "TEST").real(),
                "sin access token no hay con que cobrar");
        assertTrue(cliente().real());
        assertTrue(cliente().testMode());
        assertFalse(new MercadoPagoClient("http://mp.invalid", TOKEN, "pk", "PRODUCCION").testMode());
        assertEquals("APP_USR-0e4b2202-9b14-4b4b-9696-28802be3048a", cliente().getPublicKey());
    }

    @Test
    void mercado_pago_solo_se_queda_con_una_vuelta_https() {
        assertFalse(MercadoPagoClient.vueltaQueMercadoPagoAcepta("http://localhost:5173/vuelta"));
        assertFalse(MercadoPagoClient.vueltaQueMercadoPagoAcepta("http://databridge.cl/vuelta"));
        assertFalse(MercadoPagoClient.vueltaQueMercadoPagoAcepta(null));
        assertFalse(MercadoPagoClient.vueltaQueMercadoPagoAcepta(""));
        assertTrue(MercadoPagoClient.vueltaQueMercadoPagoAcepta("https://databridge.cl/vuelta"));
        assertTrue(MercadoPagoClient.vueltaQueMercadoPagoAcepta("  HTTPS://databridge.cl/vuelta  "));
    }
}
