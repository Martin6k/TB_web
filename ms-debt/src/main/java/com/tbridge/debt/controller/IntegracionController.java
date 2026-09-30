package com.tbridge.debt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.request.CarteraCsvForm;
import com.tbridge.debt.dto.request.CarteraV1;
import com.tbridge.debt.dto.request.MandatoRequest;
import com.tbridge.debt.dto.request.SuscripcionRequest;
import com.tbridge.debt.dto.response.CampanaResponse;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.CuentaResponse;
import com.tbridge.debt.dto.response.MandatoResponse;
import com.tbridge.debt.dto.response.SuscripcionResponse;
import com.tbridge.debt.exception.ErrorContrato;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.CarteraCsv;
import com.tbridge.debt.service.CarteraIntakeService;
import com.tbridge.debt.service.EventosService;
import com.tbridge.debt.service.MandatoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * El borde del contrato de integracion (docs/integracion).
 *
 * <p>Se autentica con clave de API, no con la sesion del portal: quien entrega
 * cartera es un sistema, no una persona. Y el emisor sale de la clave, nunca
 * del cuerpo: un campo del cuerpo se puede falsificar, la clave no.
 *
 * <p>Las respuestas no llevan enlaces de HATEOAS a proposito: la forma de este
 * contrato esta publicada y la leen sistemas de otras empresas.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Contrato v1", description = "Cartera, mandatos, campanas y suscripcion a eventos. Con clave de API")
@SecurityRequirement(name = OpenApiConfig.CLAVE_API)
@ApiResponse(responseCode = "401", description = "Clave de API invalida o revocada",
        content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
public class IntegracionController {

    private final ApiKeyService claves;
    private final CarteraIntakeService carteras;
    private final MandatoService mandatos;
    private final EventosService eventos;
    private final CuentaResponse.Receptor receptor;

    public IntegracionController(ApiKeyService claves, CarteraIntakeService carteras,
                                 MandatoService mandatos, EventosService eventos,
                                 @Value("${app.receptor.nombre:DataBridge}") String nombre,
                                 @Value("${app.receptor.rut:}") String rut) {
        this.claves = claves;
        this.carteras = carteras;
        this.mandatos = mandatos;
        this.eventos = eventos;
        this.receptor = new CuentaResponse.Receptor(rut == null || rut.isBlank() ? null : rut, nombre);
    }

    /*
     * Los dos formatos van en dos metodos, pero se documentan en una sola
     * operacion: Swagger junta los dos metodos de una misma ruta, y mostraba los
     * campos del CSV como parametros y el cuerpo JSON vacio. Por eso el metodo
     * del CSV queda oculto y esta operacion declara los dos cuerpos.
     */
    @PostMapping(value = "/carteras", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Entregar una cartera (Cartera v1, en JSON o en CSV)",
            description = """
                    Cada mes, todos los clientes con contrato, deban o no: el que esta al dia viene con \
                    `cargos: []`. **Aceptacion parcial**: una deuda mal formada se rechaza sola, con su motivo, \
                    y las demas entran. **Idempotencia**: el mismo lote enviado dos veces devuelve la misma \
                    respuesta con `repetido: true`; el mismo id con otro contenido se rechaza (409). Una agencia \
                    necesita un mandato vigente sobre el acreedor.

                    Dos formatos, que entran por la misma ingesta: la **Cartera v1 en JSON**, o la **planilla \
                    del contrato** (seccion 6.6) como `multipart/form-data`, con los datos del lote en sus \
                    propios campos.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = {
                    @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CarteraV1.class)),
                    @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = CarteraCsvForm.class))}))
    @ApiResponse(responseCode = "200", description = "Cuantas entraron, cuales no y por que")
    @ApiResponse(responseCode = "400", description = """
            El envio completo no se puede procesar: la version, un lote incompleto, sin deudas, o una \
            planilla a la que le faltan columnas o cuyas filas no calzan""",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    @ApiResponse(responseCode = "403", description = "Sin mandato vigente sobre ese acreedor",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    @ApiResponse(responseCode = "404", description = "Acreedor o campana desconocidos",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    @ApiResponse(responseCode = "409", description = "Ese id de lote ya se uso con otro contenido",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public CarteraResponse carteras(
            @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false) String autorizacion,
            @RequestBody JsonNode cuerpo
    ) {
        return carteras.recibir(claves.autenticar(autorizacion), cuerpo, Batch.Source.api);
    }

    /** La planilla del contrato. Se documenta en {@link #carteras}, con sus campos en {@link CarteraCsvForm}. */
    @PostMapping(value = "/carteras", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(hidden = true)
    public CarteraResponse carterasCsv(
            @RequestHeader(value = "Authorization", required = false) String autorizacion,
            @RequestPart("archivo") MultipartFile archivo,
            @RequestParam("lote_id_externo") String loteId,
            @RequestParam("fecha_corte") String fechaCorte,
            @RequestParam("acreedor_rut") String acreedorRut,
            @RequestParam(value = "agencia_rut", required = false) String agenciaRut,
            @RequestParam(value = "campana_id_externo", required = false) String campana
    ) throws IOException {
        CarteraCsv.Lote lote = new CarteraCsv.Lote(loteId, fechaCorte, acreedorRut, agenciaRut, campana);
        return carteras.recibir(claves.autenticar(autorizacion), CarteraCsv.leer(archivo.getBytes(), lote),
                Batch.Source.file);
    }

    @GetMapping("/cuenta")
    @Operation(summary = "Quien es el dueno de la clave",
            description = "Comprueba la clave sin mandar nada: la empresa con la que quedo conectado quien llama.")
    @ApiResponse(responseCode = "200", description = "La empresa duena de la clave")
    public CuentaResponse cuenta(
            @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false) String autorizacion
    ) {
        return CuentaResponse.from(claves.autenticar(autorizacion), receptor);
    }

    @PostMapping("/mandatos")
    @Operation(summary = "Registrar un mandato",
            description = """
                    La agencia declara que cobra por cuenta de un acreedor. Repetirlo devuelve el mismo mandato. \
                    Si DataBridge no conoce al acreedor, lo registra con `razon_social` y `nombre_fantasia`.""")
    @ApiResponse(responseCode = "200", description = "El mandato")
    @ApiResponse(responseCode = "400", description = "El RUT del acreedor no es valido",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    @ApiResponse(responseCode = "403", description = "Quien llama no es una agencia",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public MandatoResponse mandatos(
            @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false) String autorizacion,
            @RequestBody MandatoRequest pedido
    ) {
        return mandatos.registrarMandato(claves.autenticar(autorizacion), pedido);
    }

    @PostMapping("/campanas")
    @Operation(summary = "Registrar o actualizar una campana",
            description = "La estrategia de contacto de la agencia para un acreedor. Necesita un mandato vigente.")
    @ApiResponse(responseCode = "200", description = "La campana")
    @ApiResponse(responseCode = "403", description = "Sin mandato vigente sobre ese acreedor",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public CampanaResponse campanas(
            @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false) String autorizacion,
            @RequestBody CampanaRequest pedido
    ) {
        return mandatos.registrarCampana(claves.autenticar(autorizacion), pedido);
    }

    @PostMapping("/suscripciones")
    @Operation(summary = "Suscribirse a los eventos (contrato 3)",
            description = """
                    A donde avisar los eventos de las deudas que entrego quien llama. Cada aviso va firmado: \
                    `X-Firma: v1=HMAC-SHA256(secreto, X-Timestamp + "." + cuerpo)`. Registrar la misma URL \
                    otra vez devuelve el mismo secreto.""")
    @ApiResponse(responseCode = "200", description = "La suscripcion y su secreto")
    @ApiResponse(responseCode = "400", description = "URL invalida o un evento que no existe",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public SuscripcionResponse suscripciones(
            @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false) String autorizacion,
            @RequestBody SuscripcionRequest pedido
    ) {
        return eventos.suscribir(claves.autenticar(autorizacion), pedido);
    }
}
