package com.tbridge.debt.controller;

import com.tbridge.common.jwt.JwtService;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.exception.CarteraInvalidaHandler;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.CarteraIntakeService;
import com.tbridge.debt.service.EventosService;
import com.tbridge.debt.service.MandatoService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que Swagger publica del contrato v1. Lo leen sistemas de otras empresas,
 * asi que una operacion mal documentada es un contrato mal publicado.
 */
@WebMvcTest(IntegracionController.class)
@Import({SecurityConfig.class, JwtService.class, CarteraInvalidaHandler.class, OpenApiConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class})
@ActiveProfiles("test")
class IntegracionDocumentacionTest {

    private static final String CARTERAS = "$.paths['/api/v1/carteras'].post";

    @Autowired private MockMvc mvc;
    @MockitoBean private ApiKeyService claves;
    @MockitoBean private CarteraIntakeService carteras;
    @MockitoBean private MandatoService mandatos;
    @MockitoBean private EventosService eventos;

    /**
     * {@code POST /api/v1/carteras} tiene dos metodos, uno por formato. Swagger
     * los juntaba en una sola operacion con los parametros del CSV, y el cuerpo
     * JSON del contrato no aparecia.
     */
    @Test
    void la_cartera_se_documenta_en_json_y_en_csv() throws Exception {
        mvc.perform(get("/v3/api-docs/integracion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CARTERAS + ".requestBody.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/CarteraV1"))
                .andExpect(jsonPath(CARTERAS + ".requestBody.content['multipart/form-data'].schema['$ref']")
                        .value("#/components/schemas/CarteraV1Csv"))
                .andExpect(jsonPath(CARTERAS + ".parameters").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CarteraV1Csv.required")
                        .value(containsInAnyOrder("archivo", "lote_id_externo", "fecha_corte", "acreedor_rut")));
    }

    /** Los canales y la cadencia se reciben como JSON sin tipo, pero se publican como las listas que son. */
    @Test
    void la_campana_publica_sus_listas_con_su_tipo() throws Exception {
        String campana = "$.components.schemas.CampanaRequest.properties";
        mvc.perform(get("/v3/api-docs/integracion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(campana + ".canales.type").value(hasItem("array")))
                .andExpect(jsonPath(campana + ".canales['$ref']").doesNotExist())
                .andExpect(jsonPath(campana + ".canales.items.type").value("string"))
                .andExpect(jsonPath(campana + ".cadencia_dias.type").value(hasItem("array")))
                .andExpect(jsonPath(campana + ".cadencia_dias.items.type").value("integer"));
    }
}
