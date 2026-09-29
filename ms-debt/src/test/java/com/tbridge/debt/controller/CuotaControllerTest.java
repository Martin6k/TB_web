package com.tbridge.debt.controller;

import com.tbridge.common.jwt.JwtService;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.ConvenioEnRiesgoResponse;
import com.tbridge.debt.dto.response.CuotaPorVencerResponse;
import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.service.CuotaService;
import com.tbridge.debt.service.MisDatosService;
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
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Los vencimientos, los convenios en riesgo y los datos del deudor, por HTTP. */
@WebMvcTest({CuotaController.class, MisDatosController.class})
@Import({SecurityConfig.class, JwtService.class, CuotaControllerTest.Proxy.class})
@ActiveProfiles("test")
class CuotaControllerTest {

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
    @MockitoBean private CuotaService cuotas;
    @MockitoBean private MisDatosService datos;

    private String deudor() {
        return "Bearer " + jwt.issue("17893456-2", null, "DEBTOR", null, "17893456-2");
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    @Test
    void los_vencimientos_van_en_embedded_cuotas() throws Exception {
        when(cuotas.vencimientos(any())).thenReturn(List.of(new CuotaPorVencerResponse(72L, 7L, "CTR-2025-027",
                "Patrimonio Inmuebles", "Arriendo mensual", Debt.Currency.CLP, new BigDecimal("116667"),
                LocalDate.of(2026, 9, 20), 1, 6, -5, true, true, false)));

        mvc.perform(get("/api/debts/vencimientos").header("Authorization", deudor())
                        .header("X-Forwarded-Host", "localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.cuotas[0].vencida").value(true))
                .andExpect(jsonPath("$._embedded.cuotas[0].dias").value(-5))
                .andExpect(jsonPath("$._embedded.cuotas[0]._links.deuda.href").value(PUBLICA + "/api/debts/7"));
    }

    @Test
    void cada_convenio_en_riesgo_ofrece_enviarle_el_codigo() throws Exception {
        when(cuotas.enRiesgo(any())).thenReturn(List.of(new ConvenioEnRiesgoResponse(7L, "CTR-2025-027",
                "Patrimonio Inmuebles", "Ignacio Tapia Rojas", "17893456-2", Debt.Currency.CLP, 1,
                new BigDecimal("116667"), LocalDate.of(2026, 9, 20), 5, 0, 6, new BigDecimal("700000"), null)));

        mvc.perform(get("/api/debts/en-riesgo").header("Authorization", empresa())
                        .header("X-Forwarded-Host", "localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.convenios[0].diasAtraso").value(5))
                .andExpect(jsonPath("$._embedded.convenios[0]._links.enviar-codigo.href")
                        .value(PUBLICA + "/api/debts/7/codigo"));
    }

    @Test
    void los_recordatorios_se_cambian_diciendo_si_o_no() throws Exception {
        mvc.perform(patch("/api/debts/mis-datos").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Indica si quieres los recordatorios"));
        verify(datos, never()).cambiar(any(), any());

        when(datos.cambiar(any(), any())).thenReturn(new MisDatosResponse("Ignacio Tapia Rojas", "17893456-2",
                Debtor.Kind.person, "ig*********@correo.cl", null, false, 3, List.of()));
        mvc.perform(patch("/api/debts/mis-datos").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recordatorios\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordatorios").value(false))
                .andExpect(jsonPath("$.telefono").doesNotExist());
    }
}
