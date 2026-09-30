package com.tbridge.debt.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * La respuesta a una Cartera v1 (contrato, seccion 6.5).
 *
 * <p>Se guarda tal cual junto al lote: si el mismo lote llega otra vez, se
 * devuelve esta misma respuesta con {@code repetido: true}, sin volver a
 * procesar nada.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Cuantas deudas entraron, cuales no y por que")
public record CarteraResponse(
        @Schema(example = "PAT-2026-09-18-01") String lote,
        @Schema(description = "El mismo lote ya se habia recibido con el mismo contenido", example = "false")
        boolean repetido,
        @Schema(example = "4") Integer recibidas,
        @Schema(example = "3") Integer aceptadas,
        @Schema(example = "1") Integer rechazadas,
        List<ResultadoDeuda> resultados,
        @Schema(description = "Lo que vino y no se conoce: el receptor es tolerante, pero lo avisa")
        List<String> camposIgnorados
) {

    public CarteraResponse repetida() {
        return new CarteraResponse(lote, true, recibidas, aceptadas, rechazadas, resultados, camposIgnorados);
    }

    /** Lo que queda si la respuesta guardada no se puede leer: al menos, que era repetido. */
    public static CarteraResponse soloRepetida(String lote) {
        return new CarteraResponse(lote, true, null, null, null, null, null);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(description = "Lo que paso con una deuda del lote")
    public record ResultadoDeuda(
            @Schema(example = "CTR-2025-014") String idExterno,
            @Schema(allowableValues = {"registrada", "actualizada", "sin_cambios", "retirada", "al_dia",
                    "rechazada"}, description = "al_dia: el cliente no debe nada, y no se guarda",
                    example = "registrada") String resultado,
            @Schema(description = "Dias entre el cargo impago mas antiguo y la fecha de corte", nullable = true,
                    example = "44") Long moraDias,
            @Schema(allowableValues = {"1-30", "31-90", "91-120", ">120"}, nullable = true, example = "31-90")
            String tramo,
            @Schema(description = "Solo si se rechazo", nullable = true) List<ErrorDeuda> errores
    ) {
        public static ResultadoDeuda registrada(String idExterno, String resultado, long moraDias, String tramo) {
            return new ResultadoDeuda(idExterno, resultado, moraDias, tramo, null);
        }

        public static ResultadoDeuda retirada(String idExterno) {
            return new ResultadoDeuda(idExterno, "retirada", null, null, null);
        }

        /** Un cliente al dia: no hay nada que cobrarle. */
        public static ResultadoDeuda alDia(String idExterno) {
            return new ResultadoDeuda(idExterno, "al_dia", null, null, null);
        }

        public static ResultadoDeuda rechazada(String idExterno, List<ErrorDeuda> errores) {
            return new ResultadoDeuda(idExterno, "rechazada", null, null, errores);
        }

        public boolean aceptada() {
            return !"rechazada".equals(resultado);
        }
    }

    @Schema(description = "Por que se rechazo una deuda")
    public record ErrorDeuda(
            @Schema(example = "deudor.rut") String campo,
            @Schema(example = "rut_invalido") String codigo,
            @Schema(example = "El RUT no cumple el formato o el digito verificador no corresponde") String mensaje
    ) {
    }
}
