package com.tbridge.debt.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tbridge.debt.model.Organization;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * La empresa duena de una clave de API, y con quien quedo conectada.
 *
 * <p>Es lo primero que consulta un sistema al conectarse: comprueba que la
 * clave sirve, que es suya y con quien habla, sin mandar nada todavia. La
 * forma es la misma en cada tramo de la cadena (la agencia atiende la misma
 * consulta), asi que un emisor se conecta igual a cualquiera.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Quien es el dueno de la clave y con quien quedo conectado")
public record CuentaResponse(
        @Schema(example = "77305118-6") String rut,
        @Schema(example = "APOFYX SpA") String razonSocial,
        @Schema(example = "APOFYX") String nombre,
        @Schema(description = "acreedor, agencia o ambos", example = "agencia") String tipo,
        @Schema(description = "El sistema que atiende la clave") Receptor receptor
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(description = "El sistema que atiende la clave")
    public record Receptor(
            @Schema(nullable = true, example = "76123456-0") String rut,
            @Schema(example = "DataBridge") String nombre
    ) {
    }

    public static CuentaResponse from(Organization organizacion, Receptor receptor) {
        String tipo = switch (organizacion.getKind()) {
            case creditor -> "acreedor";
            case agency -> "agencia";
            case both -> "ambos";
        };
        return new CuentaResponse(organizacion.getRut(), organizacion.getLegalName(),
                organizacion.getTradeName(), tipo, receptor);
    }
}
