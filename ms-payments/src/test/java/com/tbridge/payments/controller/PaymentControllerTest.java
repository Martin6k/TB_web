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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                Payment.Gateway.webpay, Payment.Status.created, null, Instant.parse("2026-09-24T12:00:00Z"), null);
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
    void retorno_de_webpay_redirige_a_la_pantalla_de_resultado() throws Exception {
        when(payments.confirmWebpay("token123", null))
                .thenReturn(new PaymentService.WebpayCommitResult(pago(), true, "Aprobado"));

        mvc.perform(post("/api/payments/webpay/return").param("token_ws", "token123"))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                        "Location", org.hamcrest.Matchers.containsString("/pasarela/webpay/resultado?status=approved")));
    }
}
