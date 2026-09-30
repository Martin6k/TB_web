package com.tbridge.debt.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contrato 2: la estrategia de contacto de la agencia para un acreedor.
 * DataBridge la ejecuta pero no la decide.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Una campana de la agencia")
public record CampanaRequest(
        @Schema(description = "El id de la campana en el sistema de la agencia", example = "APX-CMP-8") String idExterno,
        @Schema(example = "76418902-7") String acreedorRut,
        @Schema(description = "Sin indicar, el id externo", example = "Arriendos septiembre", nullable = true) String nombre,
        @Schema(description = "Sin indicar, hoy", example = "2026-09-01", nullable = true) String inicio,
        @Schema(nullable = true, example = "2026-12-31") String fin,
        //  Se guardan tal como llegan, como texto JSON; `implementation` le dice a
        //  Swagger que son listas, porque JsonNode se publicaria como un objeto vacio.
        @Schema(implementation = String[].class, nullable = true, description = "whatsapp y/o correo",
                example = "[\"whatsapp\",\"correo\"]")
        JsonNode canales,
        @Schema(description = "Cuantas veces se contacta. Sin indicar, 3", example = "3", nullable = true) Integer intentos,
        @Schema(implementation = Integer[].class, nullable = true, description = "Dias entre contactos",
                example = "[0,3,7]")
        JsonNode cadenciaDias
) {
}
