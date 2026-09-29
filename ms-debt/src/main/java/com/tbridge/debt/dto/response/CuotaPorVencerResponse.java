package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Debt;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Una cuota por pagar, en el calendario del deudor. */
@Relation(collectionRelation = "cuotas", itemRelation = "cuota")
@Schema(description = "Una cuota pendiente, con cuanto falta para que venza")
public record CuotaPorVencerResponse(
        @Schema(example = "34") Long id,
        @Schema(example = "3") Long deudaId,
        @Schema(example = "CTR-2025-014") String externalId,
        @Schema(example = "Patrimonio Inmuebles") String acreedor,
        @Schema(example = "Arriendo mensual") String concepto,
        @Schema(example = "CLP") Debt.Currency moneda,
        @Schema(example = "173333") BigDecimal monto,
        @Schema(example = "2026-10-20") LocalDate vencimiento,
        @Schema(description = "Su lugar dentro del plan. 0 si no es de un convenio", example = "4") int lugar,
        @Schema(description = "Cuantas cuotas tiene el plan. 0 si no es de un convenio", example = "6") int deCuotas,
        @Schema(description = "Dias que faltan. Negativo si ya vencio", example = "25") long dias,
        @Schema(example = "false") boolean vencida,
        @Schema(description = "Si es cuota de un convenio, o el pago de una deuda sin convenio", example = "true")
        boolean enConvenio,
        @Schema(description = "Si es un mes que el acreedor informo despues de que el deudor acepto su convenio: "
                + "se paga aparte", example = "false")
        boolean fueraDelConvenio
) {
}
