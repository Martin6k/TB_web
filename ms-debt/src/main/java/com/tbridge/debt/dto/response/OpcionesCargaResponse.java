package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Organization;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Por cuenta de quien puede cargar cartera la empresa de la sesion: la propia
 * si es acreedora, y la de cada acreedor que le dio un mandato vigente. Es lo
 * que la pantalla ofrece para elegir, en vez de pedir que se escriba un RUT.
 */
@Schema(description = "Los acreedores y campanas para los que se puede cargar cartera")
public record OpcionesCargaResponse(
        @Schema(example = "APOFYX") String organizacion,
        List<AcreedorCarga> acreedores,
        @Schema(description = "El alcance: una deuda entra desde estos dias de mora", example = "30")
        int minDiasMora
) {

    @Schema(description = "Un acreedor por cuya cuenta se puede cargar")
    public record AcreedorCarga(
            @Schema(example = "76418902-7") String rut,
            @Schema(example = "Patrimonio Inmuebles") String nombre,
            List<CampanaCarga> campanas
    ) {
        public static AcreedorCarga de(Organization acreedor, List<Campaign> campanas) {
            return new AcreedorCarga(acreedor.getRut(), acreedor.getTradeName(),
                    campanas.stream().map(c -> new CampanaCarga(c.getExternalId(), c.getName())).toList());
        }
    }

    @Schema(description = "Una campana de ese acreedor")
    public record CampanaCarga(
            @Schema(example = "APX-CMP-8") String idExterno,
            @Schema(example = "Arriendos septiembre") String nombre
    ) {
    }
}
