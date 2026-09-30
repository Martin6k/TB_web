package com.tbridge.debt.controller;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtService;
import com.tbridge.debt.assembler.DebtModelAssembler;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.DebtDetailResponse;
import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.service.AccesoService;
import com.tbridge.debt.service.CertificateService;
import com.tbridge.debt.service.DebtService;
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

/**
 * Las deudas por HTTP: la lista en _embedded.debts, los enlaces segun quien
 * mira y el estado, la direccion publica en los enlaces, y los codigos de
 * error.
 */
@WebMvcTest(DebtController.class)
@Import({SecurityConfig.class, JwtService.class, DebtModelAssembler.class, DebtControllerTest.Proxy.class})
@ActiveProfiles("test")
class DebtControllerTest {

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
    @MockitoBean private DebtService debts;
    @MockitoBean private CertificateService certificates;
    @MockitoBean private AccesoService acceso;

    private String deudor() {
        return "Bearer " + jwt.issue("76991245-2", null, "DEBTOR", null, "76991245-2");
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private static DebtSummaryResponse deuda(Debt.Status estado) {
        return new DebtSummaryResponse(3L, "CTR-2024-007", "Patrimonio Inmuebles", "76418902-7",
                "Comercial Nandu SpA", "76991245-2", "Arriendo local comercial", Debt.Currency.UF,
                new BigDecimal("115.50"), new BigDecimal("115.50"), BigDecimal.ZERO, estado,
                Instant.parse("2026-09-24T12:00:00Z"), 0, 1, false, 0, null);
    }

    @Test
    void la_lista_va_en_embedded_debts_con_enlaces_a_la_direccion_publica() throws Exception {
        when(debts.listFor(any())).thenReturn(List.of(deuda(Debt.Status.open)));

        mvc.perform(get("/api/debts").header("Authorization", deudor()).header("X-Forwarded-Host", "localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.debts[0].externalId").value("CTR-2024-007"))
                .andExpect(jsonPath("$._embedded.debts[0].saldo").value(115.50))
                .andExpect(jsonPath("$._embedded.debts[0].cuotasPagadas").value(0))
                .andExpect(jsonPath("$._embedded.debts[0].cuotasTotales").value(1))
                .andExpect(jsonPath("$._embedded.debts[0]._links.self.href").value(PUBLICA + "/api/debts/3"))
                .andExpect(jsonPath("$._links.self.href").value(PUBLICA + "/api/debts"));
    }

    @Test
    void al_deudor_con_una_deuda_pendiente_se_le_ofrece_simular_repactar_y_pagar() throws Exception {
        when(debts.listFor(any())).thenReturn(List.of(deuda(Debt.Status.open)));

        mvc.perform(get("/api/debts").header("Authorization", deudor()).header("X-Forwarded-Host", "localhost:8080"))
                .andExpect(jsonPath("$._embedded.debts[0]._links.simular.href").value(PUBLICA + "/api/debts/3/simulate{?months}"))
                .andExpect(jsonPath("$._embedded.debts[0]._links.simular.templated").value(true))
                .andExpect(jsonPath("$._embedded.debts[0]._links.repactar.href").value(PUBLICA + "/api/debts/3/repact"))
                .andExpect(jsonPath("$._embedded.debts[0]._links.pagar.href").value(PUBLICA + "/api/payments/checkout"))
                .andExpect(jsonPath("$._embedded.debts[0]._links.enviar-codigo").doesNotExist())
                .andExpect(jsonPath("$._embedded.debts[0]._links.certificado").doesNotExist());
    }

    @Test
    void a_la_empresa_se_le_ofrece_enviar_el_codigo_y_no_pagar() throws Exception {
        when(debts.listFor(any())).thenReturn(List.of(deuda(Debt.Status.repacted)));

        mvc.perform(get("/api/debts").header("Authorization", empresa()))
                .andExpect(jsonPath("$._embedded.debts[0]._links.enviar-codigo.href").value("http://localhost/api/debts/3/codigo"))
                .andExpect(jsonPath("$._embedded.debts[0]._links.pagar").doesNotExist())
                .andExpect(jsonPath("$._embedded.debts[0]._links.repactar").doesNotExist());
    }

    @Test
    void una_deuda_pagada_solo_ofrece_el_certificado() throws Exception {
        when(debts.getFor(any(), eq(3L))).thenReturn(new DebtDetailResponse(deuda(Debt.Status.paid),
                List.of(), List.of(), List.of()));

        mvc.perform(get("/api/debts/3").header("Authorization", deudor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("paid"))
                .andExpect(jsonPath("$.cargos").isArray())
                .andExpect(jsonPath("$._links.certificado.href").value("http://localhost/api/debts/3/certificate"))
                .andExpect(jsonPath("$._links.deudas.href").value("http://localhost/api/debts"))
                .andExpect(jsonPath("$._links.pagar").doesNotExist());
    }

    @Test
    void sin_sesion_es_401_y_con_la_deuda_de_otro_403() throws Exception {
        mvc.perform(get("/api/debts")).andExpect(status().isUnauthorized());

        when(debts.getFor(any(), eq(9L))).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "No puedes ver esta deuda"));
        mvc.perform(get("/api/debts/9").header("Authorization", deudor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No puedes ver esta deuda"));
    }

    @Test
    void repactar_fuera_de_3_a_24_meses_es_400_sin_tocar_nada() throws Exception {
        mvc.perform(post("/api/debts/3/repact").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"months\":36}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Las cuotas deben estar entre 3 y 24 meses"));
        verify(debts, never()).applyRepact(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void un_id_que_no_es_numero_es_400() throws Exception {
        mvc.perform(get("/api/debts/abc").header("Authorization", deudor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El parametro 'id' no tiene el formato esperado"));
    }
}
