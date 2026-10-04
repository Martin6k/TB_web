package com.tbridge.payments.controller;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtService;
import com.tbridge.payments.assembler.PaymentModelAssembler;
import com.tbridge.payments.config.SecurityConfig;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PaymentController.class, WebhookController.class})
@Import({SecurityConfig.class, JwtService.class, PaymentModelAssembler.class, PaymentControllerTest.Proxy.class})
@ActiveProfiles("test")
class PaymentControllerTest {

    @TestConfiguration
    static class Proxy {
        @Bean
        ForwardedHeaderFilter forwardedHeaderFilter() {
            return new ForwardedHeaderFilter();
        }
    }

    private static final String RUT = "16482337-7";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @MockitoBean private PaymentService payments;

    private String deudor() {
        return "Bearer " + jwt.issue(RUT, null, "DEBTOR", null, RUT);
    }

    private static PaymentResponse pago() {
        return new PaymentResponse(41L, 3L, null, new BigDecimal("410000"), Payment.Currency.CLP, 410000L, null,
                Payment.Gateway.khipu, Payment.Status.created, null, Instant.parse("2026-09-24T12:00:00Z"), null, false);
    }

    @Test
    void abrir_un_cobro_devuelve_el_pago_la_pasarela_y_los_enlaces_publicos() throws Exception {
        when(payments.checkout(any(), any())).thenReturn(pago().conEnlaceDePago("http://localhost:8080/pasarela/41?sig=x"));

        mvc.perform(post("/api/payments/checkout").header("Authorization", deudor())
                        .header("X-Forwarded-Host", "localhost:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtId\":3,\"gateway\":\"webpay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.checkoutUrl").value("http://localhost:8080/pasarela/41?sig=x"))
                //  Sin UF no viene el valor de la UF: ni en null.
                .andExpect(jsonPath("$.ufValue").doesNotExist())
                .andExpect(jsonPath("$._links.self.href").value("http://localhost:8080/api/payments/41"))
                .andExpect(jsonPath("$._links.historia.href").value("http://localhost:8080/api/payments/41/historia"))
                .andExpect(jsonPath("$._links.deuda.href").value("http://localhost:8080/api/debts/3"));
    }

    @Test
    void sin_sesion_no_se_abre_ningun_cobro() throws Exception {
        mvc.perform(post("/api/payments/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtId\":3,\"gateway\":\"webpay\"}"))
                .andExpect(status().isUnauthorized());
        verify(payments, never()).checkout(any(), any());
    }

    @Test
    void sin_deuda_o_con_una_pasarela_inventada_es_400() throws Exception {
        mvc.perform(post("/api/payments/checkout").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gateway\":\"webpay\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Indica que deuda quieres pagar"));
        mvc.perform(post("/api/payments/checkout").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"debtId\":3,\"gateway\":\"paypal\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Pasarela no soportada (Mercado Pago, Khipu o Webpay)"));
    }

    @Test
    void mas_de_24_cuotas_o_una_cuota_sin_id_es_400() throws Exception {
        String veinticinco = java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/api/payments/checkout").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtId\":3,\"installmentIds\":[" + veinticinco + "],\"gateway\":\"webpay\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Se pueden pagar hasta 24 cuotas a la vez"));
        mvc.perform(post("/api/payments/checkout").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtId\":3,\"installmentIds\":[12,null],\"gateway\":\"webpay\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Esa cuota no existe"));
        verify(payments, never()).checkout(any(), any());
    }

    @Test
    void un_campo_que_no_existe_no_se_ignora_se_rechaza() throws Exception {
        //  El error real: el portal mandaba las cuotas con el nombre viejo, el
        //  servidor no lo veia y, sin cuotas, cobraba todo el saldo.
        mvc.perform(post("/api/payments/checkout").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtId\":3,\"installmentId\":[12,13],\"gateway\":\"webpay\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La peticion trae un campo que no existe: 'installmentId'"));
        verify(payments, never()).checkout(any(), any());
    }

    @Test
    void la_deuda_de_otro_es_403() throws Exception {
        when(payments.checkout(any(), any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Esa deuda no es tuya"));

        mvc.perform(post("/api/payments/checkout").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"debtId\":9,\"gateway\":\"webpay\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Esa deuda no es tuya"));
    }

    @Test
    void la_lista_va_en_embedded_payments() throws Exception {
        when(payments.list(any())).thenReturn(List.of(pago()));

        mvc.perform(get("/api/payments").header("Authorization", deudor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.payments[0].id").value(41))
                .andExpect(jsonPath("$._links.self.href").value("http://localhost/api/payments"));
    }

    @Test
    void la_pagina_de_la_pasarela_no_pide_sesion_sino_la_firma() throws Exception {
        when(payments.publicGet(41L, "firma")).thenReturn(pago());
        when(payments.publicGet(eq(41L), eq("otra"))).thenThrow(
                new ApiException(HttpStatus.UNAUTHORIZED, "Firma de checkout invalida"));

        mvc.perform(get("/api/payments/public/41").param("sig", "firma")).andExpect(status().isOk());
        mvc.perform(get("/api/payments/public/41").param("sig", "otra")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/public/41")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el parametro 'sig'"));
    }

    @Test
    void el_aviso_de_la_pasarela_llega_sin_sesion() throws Exception {
        when(payments.webhook(any(), eq("firma"))).thenReturn(pago());

        mvc.perform(post("/api/payments/webhooks/webpay").header("X-Signature", "firma")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":41,\"txnId\":\"wp-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41));
    }

    @Test
    void la_pagina_de_webpay_es_html_y_publica() throws Exception {
        when(payments.paginaWebpay(41L, "firma")).thenReturn("<form action=\"https://webpay3gint.transbank.cl\"></form>");

        mvc.perform(get("/api/payments/public/41/webpay").param("sig", "firma"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("webpay3gint.transbank.cl")));
    }

    @Test
    void la_vuelta_de_webpay_redirige_al_resultado_por_get_y_por_post() throws Exception {
        when(payments.retornoWebpay("tok", null, null)).thenReturn("http://localhost:8080/pasarela/41?sig=x");
        when(payments.retornoWebpay(null, "tbk", "ORD41T123")).thenReturn("http://localhost:8080/pasarela/41?sig=x");

        //  Pago: Webpay vuelve por GET con token_ws.
        mvc.perform(get("/api/payments/public/webpay/retorno").param("token_ws", "tok"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8080/pasarela/41?sig=x"));
        //  Anulado: vuelve por POST, con TBK_TOKEN y la orden.
        mvc.perform(post("/api/payments/public/webpay/retorno")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("TBK_TOKEN", "tbk").param("TBK_ORDEN_COMPRA", "ORD41T123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8080/pasarela/41?sig=x"));
    }

    @Test
    void ya_no_hay_un_commit_de_webpay_publico() throws Exception {
        //  Confirmar un token cualquiera sin sesion devolvia el pago a quien lo pidiera.
        mvc.perform(post("/api/payments/webpay/commit").param("token", "tok")).andExpect(status().isUnauthorized());
    }

    @Test
    void la_pagina_del_resultado_le_pregunta_a_khipu_sin_sesion() throws Exception {
        when(payments.verificar(41L, "firma")).thenReturn(pago());
        when(payments.cancelar(41L, "firma")).thenReturn(pago());

        mvc.perform(post("/api/payments/public/41/verificar").param("sig", "firma"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41));
        mvc.perform(post("/api/payments/public/41/cancelar").param("sig", "firma"))
                .andExpect(status().isOk());
        verify(payments).verificar(41L, "firma");
        verify(payments).cancelar(41L, "firma");
    }

    @Test
    void el_aviso_de_khipu_llega_con_el_cuerpo_tal_como_vino() throws Exception {
        String cuerpo = "{\"payment_id\": \"gqzdy6chjne9\",  \"amount\":\"1000.0000\"}";

        mvc.perform(post("/api/payments/public/khipu/aviso").header("x-khipu-signature", "t=1,s=abc")
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk());

        //  La firma va sobre el texto exacto: con sus espacios, sin reserializar.
        verify(payments).avisoDeKhipu(cuerpo, "t=1,s=abc", "gqzdy6chjne9");
    }

    @Test
    void un_aviso_de_khipu_con_firma_mala_es_401() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Firma de Khipu invalida"))
                .when(payments).avisoDeKhipu(any(), any(), any());

        mvc.perform(post("/api/payments/public/khipu/aviso").header("x-khipu-signature", "t=1,s=mala")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payment_id\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Firma de Khipu invalida"));
    }

    @Test
    void la_vuelta_de_mercadopago_redirige_al_resultado() throws Exception {
        when(payments.retornoMercadoPago("12345", "approved", null, "41", "pref_1"))
                .thenReturn("http://localhost:8080/pasarela/41?sig=x");

        mvc.perform(get("/api/payments/public/mercadopago/retorno")
                        .param("payment_id", "12345")
                        .param("status", "approved")
                        .param("external_reference", "41")
                        .param("preference_id", "pref_1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8080/pasarela/41?sig=x"));
    }

    @Test
    void el_aviso_de_mercadopago_se_procesa_sin_sesion() throws Exception {
        mvc.perform(post("/api/payments/public/mercadopago/aviso")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"payment.created\",\"data\":{\"id\":\"12345\"}}"))
                .andExpect(status().isOk());

        verify(payments).avisoMercadoPago(isNull(), isNull(), anyString());
    }
}
