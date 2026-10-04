package com.tbridge.debt.controller;

import com.tbridge.common.exception.ApiError;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.assembler.DebtModelAssembler;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.DisputaRequest;
import com.tbridge.debt.dto.request.RepactRequest;
import com.tbridge.debt.dto.request.ResolucionDisputaRequest;
import com.tbridge.debt.dto.response.CodigoEnviadoResponse;
import com.tbridge.debt.dto.response.DebtDetailResponse;
import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.dto.response.SimulacionResponse;
import com.tbridge.debt.service.AccesoService;
import com.tbridge.debt.service.CertificateService;
import com.tbridge.debt.service.DebtService;
import com.tbridge.debt.service.DisputaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las deudas del portal: lo que ve el deudor y lo que ve la empresa.
 *
 * <p>La cartera no entra por aqui: llega por el contrato de integracion
 * ({@code /api/v1/carteras}) o por la carga de archivo.
 */
@RestController
@RequestMapping("/api/debts")
@Tag(name = "Deudas", description = "Ver, simular, repactar y certificar deudas")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class DebtController {

    private final DebtService debts;
    private final CertificateService certificates;
    private final AccesoService acceso;
    private final DisputaService disputas;
    private final DebtModelAssembler enlaces;

    public DebtController(DebtService debts, CertificateService certificates, AccesoService acceso,
                          DisputaService disputas, DebtModelAssembler enlaces) {
        this.debts = debts;
        this.certificates = certificates;
        this.acceso = acceso;
        this.disputas = disputas;
        this.enlaces = enlaces;
    }

    @GetMapping
    @Operation(summary = "Las deudas de quien pregunta",
            description = """
                    Al deudor, las suyas; a la empresa, la cartera que opera (como acreedora o como agencia \
                    que la entrego). Van en `_embedded.debts`, cada una con los enlaces de lo que se puede hacer.""")
    @ApiResponse(responseCode = "200", description = "Las deudas, de la mas reciente a la mas antigua")
    @ApiResponse(responseCode = "401", description = "Sin sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "El deudor no tiene deudas registradas",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CollectionModel<EntityModel<DebtSummaryResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return enlaces.toCollectionModel(debts.listFor(user));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Una deuda completa", description = "Con sus cargos, sus cuotas y su historia.")
    @ApiResponse(responseCode = "200", description = "La deuda")
    @ApiResponse(responseCode = "403", description = "No es de quien pregunta",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<DebtDetailResponse> one(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                               @PathVariable Long id) {
        return enlaces.toModel(debts.getFor(user, id));
    }

    @GetMapping("/{id}/simulate")
    @Operation(summary = "Simular un plan de cuotas",
            description = "Sin intereses: el total es el saldo de hoy, y la ultima cuota absorbe el redondeo. No compromete nada.")
    @ApiResponse(responseCode = "200", description = "El plan")
    @ApiResponse(responseCode = "400", description = "Plazo fuera de 3 a 24 meses, o sin saldo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<SimulacionResponse> simulate(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @PathVariable Long id,
            @Parameter(description = "Cuantas cuotas, de 3 a 24", example = "6")
            @RequestParam(defaultValue = "12") Integer months
    ) {
        return enlaces.toModel(id, new SimulacionResponse(debts.simulate(user, id, months)));
    }

    @PostMapping("/{id}/repact")
    @Operation(summary = "Aceptar un plan de cuotas",
            description = """
                    Solo el deudor. Las cuotas pendientes se anulan (no se borran) y se emiten las del plan; \
                    la empresa se entera por el evento `repactacion.aceptada`.""")
    @ApiResponse(responseCode = "200", description = "La deuda con sus cuotas nuevas")
    @ApiResponse(responseCode = "400", description = "Plazo fuera de 3 a 24 meses",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Quien acepta no es el deudor",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "La deuda ya esta pagada o fue retirada",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<DebtDetailResponse> repact(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody RepactRequest pedido) {
        return enlaces.toModel(debts.applyRepact(user, id, pedido.mesesOPorOmision()));
    }

    @PostMapping("/{id}/disputa")
    @Operation(summary = "No reconozco esta deuda",
            description = """
                    Solo el deudor, con un motivo: no_reconoce, ya_pagada, monto_incorrecto u otro. Mientras la                     empresa la revisa, la deuda queda `disputed`: no se paga ni se repacta, y no le llegan                     recordatorios. La cadena se entera por el evento `deuda.disputada`, que lleva solo el motivo.""")
    @ApiResponse(responseCode = "200", description = "La deuda, en disputa")
    @ApiResponse(responseCode = "400", description = "Un motivo que no existe",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Quien disputa no es el deudor",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "La deuda ya esta pagada, retirada o en revision",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<DebtDetailResponse> disputar(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody DisputaRequest pedido) {
        return enlaces.toModel(disputas.disputar(user, id, pedido));
    }

    @PostMapping("/{id}/disputa/resolucion")
    @Operation(summary = "Resolver una disputa",
            description = """
                    Solo la empresa que opera la deuda. `reanudar`: la deuda corresponde y vuelve a cobranza,                     con su convenio si tenia uno (evento `deuda.reanudada`). `retirar`: no corresponde y sale de                     la cobranza con el motivo `disputa_resuelta` (evento `deuda.retirada`).""")
    @ApiResponse(responseCode = "200", description = "La deuda, resuelta")
    @ApiResponse(responseCode = "403", description = "Quien resuelve no es la empresa que opera la deuda",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "La deuda no esta en disputa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<DebtDetailResponse> resolverDisputa(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @PathVariable Long id,
            @Valid @RequestBody ResolucionDisputaRequest pedido) {
        return enlaces.toModel(disputas.resolver(user, id, pedido));
    }

    @PostMapping("/{id}/codigo")
    @Operation(summary = "Hacerle llegar al deudor su codigo de acceso",
            description = "Solo la empresa. El codigo va al correo del deudor y no vuelve en la respuesta: quien lo viera podria entrar en su lugar.")
    @ApiResponse(responseCode = "200", description = "A donde se mando")
    @ApiResponse(responseCode = "403", description = "Quien pide no es la empresa que opera la deuda",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "La deuda ya no esta en cobranza, o el deudor no tiene correo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "ms-auth no respondio",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CodigoEnviadoResponse codigo(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                        @PathVariable Long id) {
        return acceso.enviarCodigo(user, id);
    }

    @GetMapping(value = "/{id}/certificate", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Certificado de deuda pagada (PDF)",
            description = "Solo para deudas pagadas por completo. Una deuda retirada tambien queda en cero, pero no se pago.")
    @ApiResponse(responseCode = "200", description = "El PDF", content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE))
    @ApiResponse(responseCode = "409", description = "La deuda no esta pagada",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<byte[]> certificate(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                              @PathVariable Long id) {
        byte[] pdf = certificates.generate(user, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"certificado-deuda-cero.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
