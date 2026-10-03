package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Installment;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Una deuda en la lista del portal.
 *
 * <p>El saldo se calcula (las cuotas pendientes), nunca se guarda: un total
 * almacenado al lado de sus partes termina discrepando de ellas.
 */
@Relation(collectionRelation = "debts", itemRelation = "debt")
@Schema(description = "Una deuda, con su saldo al dia")
public record DebtSummaryResponse(
        @Schema(example = "3") Long id,
        @Schema(description = "El id que le puso el acreedor. Viaja intacto por toda la cadena", example = "CTR-2024-007")
        String externalId,
        @Schema(example = "Patrimonio Inmuebles") String acreedor,
        @Schema(example = "76418902-7") String acreedorRut,
        @Schema(example = "Comercial Nandu SpA") String deudor,
        @Schema(example = "76991245-2") String deudorRut,
        @Schema(example = "Arriendo local comercial") String concepto,
        @Schema(example = "UF") Debt.Currency moneda,
        @Schema(example = "115.50") BigDecimal montoOriginal,
        @Schema(description = "La suma de las cuotas pendientes", example = "96.25") BigDecimal saldo,
        @Schema(example = "19.25") BigDecimal pagado,
        @Schema(example = "repacted") Debt.Status estado,
        Instant actualizada,
        @Schema(description = "Cuotas ya pagadas. Con cuotasTotales, el avance del convenio", example = "1")
        int cuotasPagadas,
        @Schema(description = "Cuotas vigentes: las pagadas mas las pendientes. Las anuladas no cuentan, y en un "
                + "convenio tampoco una cuota aparte", example = "6")
        int cuotasTotales,
        @Schema(description = "Si se paga (o se pago) con un convenio de cuotas", example = "true")
        boolean conConvenio,
        @Schema(description = "Cuotas pendientes que ya vencieron. En un convenio, las que hay que ponerse al dia",
                example = "1")
        int cuotasVencidas,
        @Schema(description = "La ultima vez que se le envio el codigo de acceso, sola (al entrar la deuda) o "
                + "desde el portal. Solo para la empresa; vacio si nunca se le envio", nullable = true)
        Instant codigoEnviado,
        @Schema(description = "Solo si la deuda esta en disputa: el motivo, lo que explico el deudor y desde cuando",
                nullable = true)
        Disputa disputa
) {

    /** Por que el deudor no reconoce la deuda. */
    @Schema(name = "DisputaDeDeuda", description = "La disputa abierta de una deuda")
    public record Disputa(
            @Schema(description = "no_reconoce, ya_pagada, monto_incorrecto u otro", example = "ya_pagada")
            String motivo,
            @Schema(nullable = true, example = "Pague agosto en la oficina el 10 de septiembre") String detalle,
            Instant desde
    ) {}

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private static BigDecimal suma(List<Installment> cuotas, Installment.Status estado) {
        return cuotas.stream().filter(c -> c.getStatus() == estado).map(Installment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Con las cuotas de la deuda: de ellas salen el saldo y el avance. */
    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas) {
        return from(deuda, cuotas, null, null);
    }

    /** Para la empresa, ademas, cuando se le envio el codigo al deudor. */
    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas, Instant codigoEnviado) {
        return from(deuda, cuotas, codigoEnviado, null);
    }

    public static DebtSummaryResponse from(Debt deuda, List<Installment> cuotas, Instant codigoEnviado,
                                           Disputa disputa) {
        BigDecimal saldo = suma(cuotas, Installment.Status.pending);
        //  Lo pagado es lo que se pago por DataBridge. No sale de restar el saldo
        //  al monto original: cuando el acreedor actualiza la deuda, su monto ya
        //  viene descontado de lo que se pago aca.
        BigDecimal pagado = suma(cuotas, Installment.Status.paid);
        boolean conConvenio = (deuda.getStatus() == Debt.Status.repacted || deuda.getStatus() == Debt.Status.paid)
                && cuotas.stream().anyMatch(c -> c.getStatus() != Installment.Status.void_ && c.enConvenio());
        //  En convenio el avance se cuenta sobre las cuotas del plan: una cuota
        //  aparte (un mes que el acreedor informo despues) no es parte de el.
        List<Installment> delAvance = cuotas.stream()
                .filter(c -> c.getStatus() != Installment.Status.void_)
                .filter(c -> !conConvenio || c.enConvenio())
                .toList();
        int pagadas = (int) delAvance.stream().filter(c -> c.getStatus() == Installment.Status.paid).count();
        int vigentes = delAvance.size();
        LocalDate hoy = LocalDate.now(CHILE);
        int vencidas = (int) cuotas.stream()
                .filter(c -> c.getStatus() == Installment.Status.pending && c.getDueDate().isBefore(hoy)).count();
        return new DebtSummaryResponse(
                deuda.getId(),
                deuda.getExternalId(),
                deuda.getCreditor().getTradeName(),
                deuda.getCreditor().getRut(),
                deuda.getDebtor().getFullName(),
                deuda.getDebtor().getRut(),
                deuda.getConcept(),
                deuda.getCurrency(),
                deuda.getOriginalAmount(),
                saldo,
                pagado,
                deuda.getStatus(),
                deuda.getUpdatedAt(),
                pagadas,
                vigentes,
                conConvenio,
                vencidas,
                codigoEnviado,
                deuda.getStatus() == Debt.Status.disputed ? disputa : null);
    }
}
