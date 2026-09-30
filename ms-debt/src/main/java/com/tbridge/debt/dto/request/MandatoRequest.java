package com.tbridge.debt.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contrato 2: la agencia declara que cobra por cuenta de un acreedor.
 *
 * <p>Las fechas llegan como texto y las interpreta el servicio, con el mismo
 * criterio que la version 1 del contrato: una fecha que no se entiende toma
 * el valor por omision en vez de rechazar el mandato.
 *
 * <p>Los nombres del acreedor sirven la primera vez: si DataBridge no lo
 * conoce, lo registra con ellos. Llevan los mismos nombres que
 * {@code lote.acreedor} en la cartera.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "El mandato de la agencia sobre un acreedor")
public record MandatoRequest(
        @Schema(example = "76418902-7") String acreedorRut,
        @Schema(description = "Para registrar al acreedor si DataBridge no lo conoce. Sin indicar, el RUT",
                example = "Patrimonio Inmuebles SpA", nullable = true) String razonSocial,
        @Schema(description = "El nombre que ve el deudor. Sin indicar, la razon social",
                example = "Patrimonio Inmuebles", nullable = true) String nombreFantasia,
        @Schema(description = "Sin indicar, hoy", example = "2026-09-01", nullable = true) String vigenteDesde,
        @Schema(description = "Sin indicar, indefinido", example = "2027-08-31", nullable = true) String vigenteHasta,
        @Schema(description = "Pasados estos dias de mora el caso vuelve al acreedor. Sin indicar, 120",
                example = "120", nullable = true) Integer moraMaximaDias
) {
}
