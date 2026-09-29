package com.tbridge.debt.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que un pago aplicado lleva en {@code debt_events.detail}.
 *
 * <p>Las columnas del evento dicen cuanto y en que moneda; esto dice el resto,
 * que es lo que el deudor quiere ver en su historial: por donde pago, cuanto
 * fue en pesos si la deuda es en UF, y que cuotas cubrio.
 *
 * <p>Las cuotas van por su lugar dentro del convenio ("la 4 de 6") y no por su
 * numero interno, que corre entre planes. Se guardan al momento del pago: si
 * despues se repacta, este pago sigue diciendo lo que pago entonces.
 *
 * @param pagoId   el id del pago en ms-payments
 * @param montoClp los pesos cobrados; en UF, al valor de ese dia
 * @param valorUf  la UF usada, si la deuda es en UF
 * @param pasarela webpay, mercadopago o khipu
 * @param cuotas   el lugar de cada cuota pagada, de menor a mayor
 * @param de       cuantas cuotas tenia la deuda en ese momento
 * @param fuera    cuantas cuotas aparte, fuera del convenio, cubrio el pago: los meses que el acreedor informo
 *                 despues de que el deudor acepto su convenio. Null si ninguna
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DetallePago(
        Long pagoId,
        Long montoClp,
        BigDecimal valorUf,
        String pasarela,
        List<Integer> cuotas,
        Integer de,
        Integer fuera
) {
}
