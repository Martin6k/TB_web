package com.tbridge.debt.controller;

import com.tbridge.common.jwt.JwtService;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.CarteraResponse.ErrorDeuda;
import com.tbridge.debt.dto.response.CarteraResponse.ResultadoDeuda;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.exception.CarteraInvalidaHandler;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.CarteraIntakeService;
import com.tbridge.debt.service.EventosService;
import com.tbridge.debt.service.MandatoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El borde del contrato v1: la clave de API decide quien envia, los errores
 * salen con la forma del contrato, y la respuesta con sus nombres en
 * snake_case y sin enlaces de HATEOAS.
 */
@WebMvcTest(IntegracionController.class)
@Import({SecurityConfig.class, JwtService.class, CarteraInvalidaHandler.class})
@ActiveProfiles("test")
class IntegracionControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ApiKeyService claves;
    @MockitoBean private CarteraIntakeService carteras;
    @MockitoBean private MandatoService mandatos;
    @MockitoBean private EventosService eventos;

    private static final String CARTERA = """
            {"version":"1.0","lote":{"id_externo":"PAT-1","fecha_corte":"2026-09-18","acreedor":{"rut":"76418902-7"}},
             "deudas":[{"id_externo":"CTR-1"}]}""";

    @Test
    void sin_clave_valida_el_error_sale_con_la_forma_del_contrato() throws Exception {
        when(claves.autenticar(any())).thenThrow(new CarteraInvalida("no_autorizado", "Clave de API invalida", 401));

        mvc.perform(post("/api/v1/carteras").contentType(MediaType.APPLICATION_JSON).content(CARTERA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.codigo").value("no_autorizado"))
                .andExpect(jsonPath("$.error.mensaje").value("Clave de API invalida"));
    }

    @Test
    void la_respuesta_conserva_los_nombres_del_contrato_y_no_trae_enlaces() throws Exception {
        Organization apofyx = new Organization();
        when(claves.autenticar("Bearer tbk_prueba")).thenReturn(apofyx);
        when(carteras.recibir(eq(apofyx), any(), eq(Batch.Source.api))).thenReturn(new CarteraResponse(
                "PAT-1", false, 2, 1, 1,
                List.of(ResultadoDeuda.registrada("CTR-1", "registrada", 44, "31-90"),
                        ResultadoDeuda.rechazada("CTR-2", List.of(new ErrorDeuda("deudor.rut", "rut_invalido", "x")))),
                List.of()));

        mvc.perform(post("/api/v1/carteras").header("Authorization", "Bearer tbk_prueba")
                        .contentType(MediaType.APPLICATION_JSON).content(CARTERA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lote").value("PAT-1"))
                .andExpect(jsonPath("$.repetido").value(false))
                .andExpect(jsonPath("$.campos_ignorados").isArray())
                .andExpect(jsonPath("$.resultados[0].id_externo").value("CTR-1"))
                .andExpect(jsonPath("$.resultados[0].mora_dias").value(44))
                .andExpect(jsonPath("$.resultados[0].errores").doesNotExist())
                .andExpect(jsonPath("$.resultados[1].errores[0].codigo").value("rut_invalido"))
                .andExpect(jsonPath("$.resultados[1].mora_dias").doesNotExist())
                .andExpect(jsonPath("$._links").doesNotExist());
    }

    @Test
    void un_lote_reutilizado_con_otro_contenido_es_409() throws Exception {
        when(claves.autenticar(any())).thenReturn(new Organization());
        when(carteras.recibir(any(), any(), any())).thenThrow(
                new CarteraInvalida("lote_id_reutilizado", "El lote PAT-1 ya se recibio con otro contenido", 409));

        mvc.perform(post("/api/v1/carteras").header("Authorization", "Bearer tbk_prueba")
                        .contentType(MediaType.APPLICATION_JSON).content(CARTERA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.codigo").value("lote_id_reutilizado"));
    }

    @Test
    void la_cuenta_dice_quien_es_el_dueno_de_la_clave() throws Exception {
        Organization apofyx = new Organization();
        apofyx.setRut("77305118-6");
        apofyx.setLegalName("APOFYX SpA");
        apofyx.setTradeName("APOFYX");
        apofyx.setKind(Organization.Kind.agency);
        when(claves.autenticar("Bearer tbk_prueba")).thenReturn(apofyx);

        mvc.perform(get("/api/v1/cuenta").header("Authorization", "Bearer tbk_prueba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rut").value("77305118-6"))
                .andExpect(jsonPath("$.razon_social").value("APOFYX SpA"))
                .andExpect(jsonPath("$.nombre").value("APOFYX"))
                .andExpect(jsonPath("$.tipo").value("agencia"))
                .andExpect(jsonPath("$.receptor.nombre").value("DataBridge"));
    }
}
