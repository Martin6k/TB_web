package com.tbridge.debt.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtCharge;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Installment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Una deuda completa: lo de la lista, mas lo que la compone.
 *
 * <p>Los cargos son el desglose con que llego (los meses de arriendo, los
 * aranceles, el tratamiento); las cuotas, lo que hay que pagar (una sola sin
 * repactar); la historia, cada cosa que le paso.
 */
@Schema(description = "Una deuda con sus cargos, sus cuotas y su historia")
public record DebtDetailResponse(
        @JsonUnwrapped DebtSummaryResponse resumen,
        List<Cargo> cargos,
        List<Cuota> cuotas,
        List<Suceso> historia
) {

    public Long id() {
        return resumen.id();
    }

    public Debt.Status estado() {
        return resumen.estado();
    }

    @Schema(description = "Un cargo del desglose original")
    public record Cargo(
            @Schema(example = "Arriendo agosto") String concepto,
            @Schema(example = "2026-08") String periodo,
            @Schema(example = "520000") BigDecimal monto,
            @Schema(example = "2026-08-05") LocalDate vencimiento
    ) {
        public static Cargo from(DebtCharge cargo) {
            return new Cargo(cargo.getConcept(), cargo.getPeriod(), cargo.getAmount(), cargo.getDueDate());
        }
    }

    @Schema(description = "Una cuota por pagar, pagada, o reemplazada por un plan")
    public record Cuota(
            @Schema(example = "12") Long id,
            @Schema(example = "2") Short numero,
            @Schema(example = "2026-10-24") LocalDate vencimiento,
            @Schema(example = "19.25") BigDecimal monto,
            @Schema(allowableValues = {"pending", "paid", "anulada"}, example = "pending") String estado,
            @Schema(nullable = true) Instant pagadaEn,
            @Schema(description = "Si es cuota de un convenio. En una deuda en convenio, una que no lo es es un mes "
                    + "que el acreedor informo despues: se paga aparte", example = "true")
            boolean enConvenio
    ) {
        public static Cuota from(Installment cuota) {
            String estado = cuota.getStatus() == Installment.Status.void_ ? "anulada" : cuota.getStatus().name();
            return new Cuota(cuota.getId(), cuota.getNumber(), cuota.getDueDate(), cuota.getAmount(), estado,
                    cuota.getPaidAt(), cuota.enConvenio());
        }
    }

    @Schema(description = "Algo que le paso a la deuda")
    public record Suceso(
            @Schema(example = "payment_applied") DebtEvent.Type tipo,
            @Schema(example = "system") DebtEvent.Actor quien,
            @Schema(nullable = true, example = "19.25") BigDecimal monto,
            @Schema(nullable = true, example = "UF") Debt.Currency moneda,
            @Schema(nullable = true, example = "webpay:wp-9f31c2") String referencia,
            Instant ocurrioEn
    ) {
        public static Suceso from(DebtEvent evento) {
            return new Suceso(evento.getType(), evento.getActor(), evento.getAmount(), evento.getCurrency(),
                    evento.getReference(), evento.getOccurredAt());
        }
    }
}
