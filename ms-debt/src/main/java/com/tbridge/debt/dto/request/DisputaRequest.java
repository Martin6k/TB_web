package com.tbridge.debt.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** El deudor no reconoce la deuda. */
@Schema(description = "Por que el deudor no reconoce la deuda")
public record DisputaRequest(
        @Schema(description = "no_reconoce, ya_pagada, monto_incorrecto u otro", example = "ya_pagada")
        @NotBlank(message = "Elige el motivo")
        String motivo,
        @Schema(description = "Lo que el deudor quiera explicar. Solo lo ve la empresa que cobra", nullable = true,
                example = "Pague agosto en la oficina el 10 de septiembre")
        @Size(max = 500, message = "La explicacion puede tener hasta 500 caracteres")
        String detalle
) {
}
