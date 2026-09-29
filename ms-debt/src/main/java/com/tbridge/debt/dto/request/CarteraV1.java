package com.tbridge.debt.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * La forma de una Cartera v1, para la documentacion.
 *
 * <p>El controlador la recibe como JSON sin tipo a proposito: el contrato pide
 * aceptacion parcial (una deuda mal formada se rechaza sola, con su motivo, y
 * las demas entran) y avisar los campos desconocidos. Un tipo fijo rechazaria
 * el lote entero ante el primer campo con otro tipo, y no podria contar lo que
 * sobra. La definicion completa esta en {@code docs/integracion/cartera-v1.schema.json}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "CarteraV1", description = "Una cartera en el formato del contrato de integracion")
public record CarteraV1(
        @Schema(example = "1.0") String version,
        Lote lote,
        List<Deuda> deudas
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "CarteraV1Lote", description = "Los datos del envio")
    public record Lote(
            @Schema(description = "Unico por emisor: la clave de idempotencia", example = "APX-2026-09-19-004")
            String idExterno,
            @Schema(description = "La mora se mide contra esta fecha", example = "2026-09-18") String fechaCorte,
            @Schema(nullable = true) String emitidoEn,
            Acreedor acreedor,
            @Schema(description = "Solo si la envia una agencia", nullable = true) Mandato mandato
    ) {
    }

    @Schema(name = "CarteraV1Acreedor")
    public record Acreedor(@Schema(example = "76418902-7") String rut) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "CarteraV1Mandato")
    public record Mandato(
            @Schema(example = "77305118-6") String agenciaRut,
            @Schema(example = "APX-CMP-8", nullable = true) String campanaIdExterno
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "CarteraV1Deuda", description = "Una deuda: registrar (por omision) o retirar")
    public record Deuda(
            @Schema(description = "El id del acreedor. Viaja intacto por toda la cadena", example = "CTR-2025-014")
            String idExterno,
            @Schema(allowableValues = {"registrar", "retirar"}, nullable = true) String accion,
            @Schema(description = "Solo al retirar", nullable = true,
                    allowableValues = {"pago_directo", "acuerdo_directo", "error", "disputa_resuelta",
                            "fuera_de_mandato", "otro"})
            String motivoRetiro,
            Deudor deudor,
            @Schema(allowableValues = {"CLP", "UF"}) String moneda,
            @Schema(example = "Arriendo mensual") String concepto,
            @Schema(description = "Se le muestran al deudor para que reconozca la deuda", nullable = true)
            Map<String, String> referencias,
            List<Cargo> cargos
    ) {
    }

    @Schema(name = "CarteraV1Deudor", description = "Al menos uno: correo o telefono")
    public record Deudor(
            @Schema(example = "16482337-7") String rut,
            @Schema(allowableValues = {"persona", "empresa"}) String tipo,
            @Schema(example = "Felipe Rojas Munoz") String nombre,
            @Schema(nullable = true, example = "felipe.rojas@correo.cl") String correo,
            @Schema(nullable = true, example = "+56987654321") String telefono
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "CarteraV1Cargo", description = "Lo que se debe hoy de ese cargo")
    public record Cargo(
            @Schema(example = "Arriendo agosto") String concepto,
            @Schema(nullable = true, example = "2026-08") String periodo,
            @Schema(description = "Enteros en CLP; hasta dos decimales en UF", example = "520000") BigDecimal monto,
            @Schema(description = "Anterior a la fecha de corte", example = "2026-08-05") String fechaVencimiento
    ) {
    }
}
