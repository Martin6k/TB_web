package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Debtor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Lo que DataBridge sabe del deudor, y de parte de quien.
 *
 * <p>Sirve para que el deudor confirme que la cobranza es legitima: si el
 * correo que aparece aca es el suyo y la empresa es con la que tiene la deuda, el
 * mensaje que recibio es de verdad. El correo y el telefono van a medias: se
 * reconocen sin quedar expuestos en una pantalla ajena.
 */
@Schema(description = "Los datos del deudor que tiene registrados el acreedor")
public record MisDatosResponse(
        @Schema(example = "Felipe Rojas Muñoz") String nombre,
        @Schema(example = "16482337-7") String rut,
        @Schema(example = "person") Debtor.Kind tipo,
        @Schema(example = "fe**********@correo.cl", nullable = true) String correo,
        @Schema(example = "+569****4321", nullable = true) String telefono,
        @Schema(description = "Si quiere el correo que le recuerda una cuota por vencer", example = "true")
        boolean recordatorios,
        @Schema(description = "Cuantos dias antes llega ese correo", example = "3") int diasAntes,
        List<Acreedor> acreedores
) {

    @Schema(description = "A quien le debe, y quien cobra por su cuenta")
    public record Acreedor(
            @Schema(example = "Patrimonio Inmuebles") String nombre,
            @Schema(example = "76418902-7") String rut,
            @Schema(description = "La agencia que cobra por encargo del acreedor, si hay una", nullable = true,
                    example = "APOFYX") String cobraPorSuCuenta,
            @Schema(example = "1") int deudas
    ) {
    }
}
