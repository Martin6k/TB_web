package com.tbridge.payments.controller;

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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * Los pagos del portal, la integracion de Webpay y la pagina publica de la pasarela simulada.
 */
@RestController
@RequestMapping("/api/payments")
@Tag(name = "Pagos", description = "Abrir un cobro, seguirlo y ver su historia")
public class PaymentController {

    private final PaymentService payments;
    private final PaymentModelAssembler enlaces;
    private final String publicUrl;

    public PaymentController(
            PaymentService payments,
            PaymentModelAssembler enlaces,
            @Value("${app.public-url}") String publicUrl
    ) {
        this.payments = payments;
        this.enlaces = enlaces;
        this.publicUrl = publicUrl.replaceAll("/$", "");
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

    @RequestMapping(value = "/webpay/return", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "Retorno desde Transbank Webpay",
            description = "Punto al que Transbank redirige al usuario con token_ws o TBK_TOKEN tras completar o anular el pago.")
    public org.springframework.http.ResponseEntity<String> webpayReturn(
            @RequestParam(name = "token_ws", required = false) String tokenWs,
            @RequestParam(name = "TBK_TOKEN", required = false) String tbkToken,
            @RequestParam(name = "tbk_token", required = false) String tbkTokenLower) {
        String token = tokenWs != null && !tokenWs.isBlank() ? tokenWs : null;
        String aborted = tbkToken != null && !tbkToken.isBlank() ? tbkToken : tbkTokenLower;

        String targetUrl;
        try {
            PaymentService.WebpayCommitResult result = payments.confirmWebpay(token, aborted);
            if (result.success() && result.payment() != null) {
                targetUrl = publicUrl + "/pasarela/webpay/resultado?status=approved&id="
                        + result.payment().id() + "&debtId=" + result.payment().debtId()
                        + "&amount=" + result.payment().amount() + "&currency=" + result.payment().currency();
            } else {
                Long debtId = result.payment() != null ? result.payment().debtId() : null;
                targetUrl = publicUrl + "/pasarela/webpay/resultado?status=rejected"
                        + (debtId != null ? "&debtId=" + debtId : "");
            }
        } catch (Exception e) {
            targetUrl = publicUrl + "/pasarela/webpay/resultado?status=error&error="
                    + UriUtils.encode(e.getMessage(), StandardCharsets.UTF_8);
        }

        String html = """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <meta charset="UTF-8">
                    <meta http-equiv="refresh" content="0;url=%s">
                    <title>Redirigiendo...</title>
                    <script>
                        window.location.replace("%s");
                    </script>
                </head>
                <body style="font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f8fafc;">
                    <div style="text-align: center;">
                        <h2>Procesando pago Transbank...</h2>
                        <p>Redirigiendo a tu comprobante. Si no eres redirigido, <a href="%s">haz clic aqu&iacute;</a>.</p>
                    </div>
                </body>
                </html>
                """.formatted(targetUrl, targetUrl, targetUrl);

        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.TEXT_HTML)
                .body(html);
    }

    @PostMapping("/webpay/commit")
    @Operation(summary = "Confirmar token Webpay manualmente via API")
    public PaymentResponse webpayCommit(@RequestParam String token) {
        return payments.confirmWebpay(token, null).payment();
    }
}
