package com.tbridge.debt.controller;

import com.tbridge.common.jwt.JwtService;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.PagoResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.service.ComprobanteService;
import com.tbridge.debt.service.HistorialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El historial por HTTP: la lista en _embedded.pagos, y el comprobante en PDF. */
@WebMvcTest(HistorialController.class)
@Import({SecurityConfig.class, JwtService.class, HistorialControllerTest.Proxy.class})
@ActiveProfiles("test")
class HistorialControllerTest {

    @TestConfiguration
    static class Proxy {
        @Bean
        ForwardedHeaderFilter forwardedHeaderFilter() {
            return new ForwardedHeaderFilter();
        }
    }

    private static final String PUBLICA = "http://localhost:8080";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @MockitoBean private HistorialService historial;
    @MockitoBean private ComprobanteService comprobantes;

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    @Test
    void los_pagos_van_en_embedded_con_su_comprobante() throws Exception {
        when(historial.pagos(any())).thenReturn(List.of(new PagoResponse(57L, 3L, "CTR-2025-014",
                "Patrimonio Inmuebles", "Felipe Rojas Muñoz", "16482337-7", "Arriendo mensual", Debt.Currency.CLP,
                new BigDecimal("346666"), 346666L, null, "webpay", "wp-9f31c2", List.of(4, 5), 6, null,
                Instant.parse("2026-09-23T15:30:00Z"))));

        mvc.perform(get("/api/debts/pagos").header("Authorization", deudor()).header("X-Forwarded-Host", "localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.pagos[0].cuotas[1]").value(5))
                .andExpect(jsonPath("$._embedded.pagos[0].pasarela").value("webpay"))
                //  Sin UF, no viene el valor de la UF: ni en null.
                .andExpect(jsonPath("$._embedded.pagos[0].valorUf").doesNotExist())
                .andExpect(jsonPath("$._embedded.pagos[0]._links.comprobante.href")
                        .value(PUBLICA + "/api/debts/pagos/57/comprobante"))
                .andExpect(jsonPath("$._embedded.pagos[0]._links.deuda.href").value(PUBLICA + "/api/debts/3"));
    }

    @Test
    void el_comprobante_se_descarga_como_pdf() throws Exception {
        when(comprobantes.generar(any(), eq(57L))).thenReturn("%PDF-1.4 prueba".getBytes());

        mvc.perform(get("/api/debts/pagos/57/comprobante").header("Authorization", deudor()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"comprobante-pago-57.pdf\""));
    }

    @Test
    void sin_sesion_no_hay_historial() throws Exception {
        mvc.perform(get("/api/debts/pagos")).andExpect(status().isUnauthorized());
    }
}
