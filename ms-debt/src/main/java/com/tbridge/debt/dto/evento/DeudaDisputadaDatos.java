package com.tbridge.debt.dto.evento;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code deuda.disputada}: el deudor dice que la deuda no es suya o no
 * corresponde. Lleva solo el codigo del motivo: lo que el deudor escribio con
 * sus palabras se queda en DataBridge, porque los eventos no llevan datos
 * personales (decision I5).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeudaDisputadaDatos(String deudaIdExterno, String motivo) {
}
