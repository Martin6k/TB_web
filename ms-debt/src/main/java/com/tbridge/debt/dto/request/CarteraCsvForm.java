package com.tbridge.debt.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * La cartera en la planilla del contrato (seccion 6.6), para la documentacion.
 *
 * <p>El controlador la recibe como {@code multipart/form-data}: el archivo por
 * un lado y los datos del lote por otro, porque la planilla trae solo las
 * deudas. Se documenta en la misma operacion que la Cartera v1 en JSON: es la
 * misma entrega en otro formato, y entra por la misma ingesta.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "CarteraV1Csv",
        description = "La misma cartera en la planilla del contrato: una fila por cargo, separada por `;`")
public record CarteraCsvForm(
        @Schema(type = "string", format = "binary", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "La planilla, en UTF-8. Un cliente al dia va en una sola fila, sin las columnas de cargo")
        String archivo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "PAT-2026-09-18-01",
                description = "Unico por emisor: la clave de idempotencia")
        String loteIdExterno,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-09-18",
                description = "La mora se mide contra esta fecha")
        String fechaCorte,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "76418902-7")
        String acreedorRut,
        @Schema(nullable = true, example = "77305118-6", description = "Solo en el tramo agencia a plataforma")
        String agenciaRut,
        @Schema(nullable = true, example = "APX-CMP-8", description = "La campana de la agencia, si la envia una")
        String campanaIdExterno
) {
}
