package com.tbridge.debt.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.response.PagoResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * El comprobante de un pago: que se pago, de que deuda, por donde y cuando.
 *
 * <p>Existe por cada pago, no solo al final. El certificado dice que la deuda
 * quedo en cero; el comprobante es lo que el deudor muestra si le preguntan
 * por la cuota de octubre.
 */
@Service
@Transactional(readOnly = true)
public class ComprobanteService {

    private static final Map<String, String> PASARELAS = Map.of(
            "webpay", "Webpay", "mercadopago", "Mercado Pago", "khipu", "Khipu");

    private final HistorialService historial;

    public ComprobanteService(HistorialService historial) {
        this.historial = historial;
    }

    public byte[] generar(JwtPrincipal user, Long pagoId) {
        DebtEvent evento = historial.pago(user, pagoId);
        PagoResponse pago = historial.respuesta(evento);
        Debt deuda = evento.getDebt();

        String nota = null;
        if (pago.moneda() == Debt.Currency.UF && pago.montoClp() != null) {
            nota = "Equivale a " + DocumentoPdf.pesos(pago.montoClp())
                    + (pago.valorUf() == null ? "" : ", con la UF de ese día (" + DocumentoPdf.pesosConDecimales(pago.valorUf()) + ")");
        }

        List<String[]> filas = new ArrayList<>();
        filas.add(new String[]{"Pagado por", deuda.getDebtor().getFullName() + ", RUT "
                + DocumentoPdf.rut(deuda.getDebtor().getRut())});
        filas.add(new String[]{"Acreedor", deuda.getCreditor().getTradeName() + ", RUT "
                + DocumentoPdf.rut(deuda.getCreditor().getRut())});
        filas.add(new String[]{"Deuda", deuda.getConcept() + ", contrato " + deuda.getExternalId()});
        filas.add(new String[]{"Qué se pagó", queSePago(pago)});
        filas.add(new String[]{"Fecha", DocumentoPdf.fechaYHora(pago.pagadoEn())});
        if (pago.pasarela() != null) {
            filas.add(new String[]{"Medio de pago", PASARELAS.getOrDefault(pago.pasarela(), pago.pasarela())
                    + (pago.referencia() == null ? "" : ", operación " + pago.referencia())});
        }

        try {
            return new DocumentoPdf()
                    .encabezado("Comprobante de pago", "Folio " + pago.id())
                    .destacado("Monto pagado", DocumentoPdf.dinero(pago.monto(), pago.moneda()), nota)
                    .datos(filas.toArray(String[][]::new))
                    .pie("DataBridge emite este comprobante con el registro del pago. "
                            + deuda.getCreditor().getTradeName() + " ya fue avisado.")
                    .cerrar();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar el comprobante");
        }
    }

    /**
     * "Cuotas 4 y 5 de 6", "Cuota 3 de 12", "El total de la deuda", o, si cubrio un mes informado despues
     * del convenio, "Fuera del convenio" y lo que haya pagado del plan.
     */
    static String queSePago(PagoResponse pago) {
        List<Integer> cuotas = pago.cuotas() == null ? List.of() : pago.cuotas();
        boolean fuera = pago.fueraDelConvenio() != null && pago.fueraDelConvenio() > 0;
        if (cuotas.isEmpty()) {
            return fuera ? "Fuera del convenio" : "Un abono a la deuda";
        }
        if (!fuera && pago.deCuotas() != null && pago.deCuotas() == 1) {
            return "El total de la deuda";
        }
        String de = pago.deCuotas() == null ? "" : " de " + pago.deCuotas();
        List<String> numeros = cuotas.stream().map(String::valueOf).toList();
        String plan = numeros.size() == 1 ? "cuota " + numeros.getFirst() + de
                : "cuotas " + String.join(", ", numeros.subList(0, numeros.size() - 1)) + " y " + numeros.getLast() + de;
        return fuera ? "Fuera del convenio y " + plan : Character.toUpperCase(plan.charAt(0)) + plan.substring(1);
    }
}
