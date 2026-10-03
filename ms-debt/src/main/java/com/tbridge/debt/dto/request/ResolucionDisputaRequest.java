package com.tbridge.debt.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lo que decide la empresa que cobra despues de revisar una disputa. */
@Schema(description = "Como se resuelve la disputa")
public record ResolucionDisputaRequest(
        @Schema(description = "reanudar (la deuda corresponde y vuelve a cobranza) o retirar (no corresponde: sale "
                + "de la cobranza con el motivo disputa_resuelta)", example = "reanudar")
        @NotBlank(message = "Elige como se resuelve")
        String resultado,
        @Schema(description = "Una nota para el registro", nullable = true, example = "El pago de agosto no aparece")
        @Size(max = 500, message = "La nota puede tener hasta 500 caracteres")
        String nota
) {
}
