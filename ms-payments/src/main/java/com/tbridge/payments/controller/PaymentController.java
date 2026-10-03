package com.tbridge.payments.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.exception.ApiError;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.payments.assembler.PaymentModelAssembler;
import com.tbridge.payments.config.OpenApiConfig;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.dto.response.HistoriaResponse;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.service.PaymentService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los pagos del portal, la pagina publica de la pasarela y el aviso de Khipu.
 */
@RestController
@RequestMapping("/api/payments")
@Tag(name = "Pagos", description = "Abrir un cobro, seguirlo y ver su historia")
public class PaymentController {

    private static final ObjectMapper LECTOR = new ObjectMapper();

    private final PaymentService payments;
    private final PaymentModelAssembler enlaces;

    public PaymentController(PaymentService payments, PaymentModelAssembler enlaces) {
        this.payments = payments;
        this.enlaces = enlaces;
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Abrir el cobro de una deuda o de una cuota",
            description = """
                    El deudor dice que deuda (o que cuota) quiere pagar y por que pasarela. El monto lo pone \
                    ms-debt, no la peticion. Devuelve el pago recien creado y `checkoutUrl`, la pagina de la \
                    pasarela a la que hay que ir.""")
    @SecurityRequirement(name = OpenApiConfig.JWT)
    @ApiResponse(responseCode = "200", description = "Cobro abierto")
    @ApiResponse(responseCode = "400", description = "Falta la deuda o la pasarela no existe",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "La deuda no es de quien pide pagarla, o quien pide es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "La deuda no tiene saldo por pagar",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "ms-debt no respondio o no hay valor de la UF de hoy: no se cobra a ciegas",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<PaymentResponse> checkout(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                                 @Valid @RequestBody CheckoutRequest pedido) {
        return enlaces.toModel(payments.checkout(user, pedido));
    }

    @GetMapping
    @Operation(summary = "Mis pagos",
            description = "Al deudor, los suyos; a una empresa, los de las deudas de las que es acreedora.")
    @SecurityRequirement(name = OpenApiConfig.JWT)
    @ApiResponse(responseCode = "200", description = "Los pagos, del mas nuevo al mas antiguo, en `_embedded.payments`")
    public CollectionModel<EntityModel<PaymentResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return enlaces.toCollectionModel(payments.list(user));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Un pago")
    @SecurityRequirement(name = OpenApiConfig.JWT)
    @ApiResponse(responseCode = "200", description = "El pago y sus enlaces")
    @ApiResponse(responseCode = "403", description = "No le corresponde verlo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<PaymentResponse> one(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                            @PathVariable Long id) {
        return enlaces.toModel(payments.get(user, id));
    }

    @GetMapping("/{id}/historia")
    @Operation(summary = "El libro de un pago", description = "Cada paso, del primero al ultimo. Nada se sobreescribe.")
    @SecurityRequirement(name = OpenApiConfig.JWT)
    @ApiResponse(responseCode = "200", description = "Los pasos del pago")
    @ApiResponse(responseCode = "403", description = "No le corresponde verlo",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<HistoriaResponse> historia(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                                  @PathVariable Long id) {
        return enlaces.toModel(id, payments.historia(user, id));
    }

    @GetMapping("/public/{id}")
    @Operation(summary = "El pago visto desde la pasarela",
            description = "Sin sesion: la pagina de la pasarela lo abre con la firma que venia en `checkoutUrl`.")
    @ApiResponse(responseCode = "200", description = "El pago")
    @ApiResponse(responseCode = "401", description = "La firma no corresponde a ese pago",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PaymentResponse pub(@PathVariable Long id,
                               @Parameter(description = "La firma del enlace de pago") @RequestParam String sig) {
        return payments.publicGet(id, sig);
    }

    @PostMapping("/public/{id}/confirm")
    @Operation(summary = "Confirmar el pago desde la pasarela simulada",
            description = "Lo que en produccion hace el aviso firmado de la pasarela. Repetirlo no cobra dos veces.")
    @ApiResponse(responseCode = "200", description = "El pago, ya pagado")
    @ApiResponse(responseCode = "401", description = "La firma no corresponde a ese pago",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PaymentResponse confirm(@PathVariable Long id,
                                   @Parameter(description = "La firma del enlace de pago") @RequestParam String sig) {
        return payments.confirmPublic(id, sig);
    }

    @PostMapping("/public/{id}/verificar")
    @Operation(summary = "Preguntarle a Khipu en que va el pago",
            description = "Khipu devuelve al deudor a la pagina del resultado sin decir nada del pago: esa pagina "
                    + "llama aca, y ms-payments le pregunta a Khipu. Un pago simulado o ya cerrado vuelve tal cual.")
    @ApiResponse(responseCode = "200", description = "El pago, al dia con lo que dice Khipu")
    @ApiResponse(responseCode = "401", description = "La firma no corresponde a ese pago",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "Khipu no respondio",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PaymentResponse verificar(@PathVariable Long id,
                                     @Parameter(description = "La firma del enlace de pago") @RequestParam String sig) {
        return payments.verificar(id, sig);
    }

    @PostMapping("/public/{id}/cancelar")
    @Operation(summary = "El deudor se arrepintio en Khipu",
            description = "Si Khipu dice que no alcanzo a pagar, el pago queda fallido; si alcanzo, vale.")
    @ApiResponse(responseCode = "200", description = "El pago, fallido o pagado")
    @ApiResponse(responseCode = "401", description = "La firma no corresponde a ese pago",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PaymentResponse cancelar(@PathVariable Long id,
                                    @Parameter(description = "La firma del enlace de pago") @RequestParam String sig) {
        return payments.cancelar(id, sig);
    }

    @PostMapping(value = "/public/khipu/aviso", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "El aviso de Khipu",
            description = """
                    Khipu avisa que un pago se concilio, firmado en `x-khipu-signature`. No se aplica lo que dice:                     se verifica la firma y se le pregunta a Khipu. Solo llega si DataBridge tiene una direccion                     publica (`KHIPU_URL_AVISOS`); en local, los pagos los concilia la consulta periodica.""")
    @ApiResponse(responseCode = "200", description = "Recibido")
    @ApiResponse(responseCode = "401", description = "Sin firma, o una que no calza",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public void avisoDeKhipu(@RequestBody String cuerpo,
                             @RequestHeader(name = "x-khipu-signature", required = false) String firma) {
        payments.avisoDeKhipu(cuerpo, firma, idDelAviso(cuerpo));
    }

    /** El payment_id del aviso. Se lee aparte porque la firma va sobre el cuerpo tal como llego. */
    private static String idDelAviso(String cuerpo) {
        try {
            JsonNode aviso = LECTOR.readTree(cuerpo);
            return aviso.hasNonNull("payment_id") ? aviso.get("payment_id").asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
