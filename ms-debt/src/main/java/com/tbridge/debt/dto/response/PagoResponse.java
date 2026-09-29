package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Debt;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Un pago ya abonado a una deuda: una fila del historial del deudor o de los
 * pagos recibidos de la empresa.
 */
@Relation(collectionRelation = "pagos", itemRelation = "pago")
@Schema(description = "Un pago abonado a una deuda")
public record PagoResponse(
        @Schema(description = "El folio del pago en DataBridge", example = "57") Long id,
        @Schema(example = "3") Long deudaId,
        @Schema(example = "CTR-2025-014") String externalId,
        @Schema(example = "Patrimonio Inmuebles") String acreedor,
        @Schema(example = "Felipe Rojas Muñoz") String deudor,
        @Schema(example = "16482337-7") String deudorRut,
        @Schema(example = "Arriendo mensual") String concepto,
        @Schema(example = "CLP") Debt.Currency moneda,
        @Schema(description = "En la moneda de la deuda", example = "346666") BigDecimal monto,
        @Schema(description = "Los pesos cobrados. En UF, al valor de ese dia", nullable = true, example = "346666")
        Long montoClp,
        @Schema(description = "La UF usada, solo en deudas en UF", nullable = true, example = "39876.54")
        BigDecimal valorUf,
        @Schema(allowableValues = {"webpay", "mercadopago", "khipu"}, nullable = true, example = "webpay")
        String pasarela,
        @Schema(description = "El id de la transaccion en la pasarela", nullable = true, example = "wp-9f31c2")
        String referencia,
        @Schema(description = "El lugar de cada cuota pagada dentro del plan", example = "[4, 5]") List<Integer> cuotas,
        @Schema(description = "Cuantas cuotas tenia el plan al pagar", nullable = true, example = "6") Integer deCuotas,
        @Schema(description = "Cuotas fuera del convenio que cubrio el pago: meses que el acreedor informo despues",
                nullable = true, example = "1") Integer fueraDelConvenio,
        Instant pagadoEn
) {
}
