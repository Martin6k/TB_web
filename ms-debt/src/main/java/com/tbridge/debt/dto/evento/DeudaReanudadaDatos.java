package com.tbridge.debt.dto.evento;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code deuda.reanudada}: la agencia reviso la disputa, la deuda corresponde
 * y vuelve a cobranza. {@code conConvenio} dice si vuelve a su convenio de
 * cuotas o queda en gestion.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeudaReanudadaDatos(String deudaIdExterno, String motivo, boolean conConvenio) {
}
