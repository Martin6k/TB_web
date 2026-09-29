package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.response.PagoResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.DetallePago;
import com.tbridge.debt.repository.DebtEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Los pagos ya abonados: el historial del deudor y los pagos recibidos de la
 * empresa.
 *
 * <p>Sale de los eventos {@code payment_applied} de ms-debt, no de ms-payments.
 * Para el deudor un pago existe cuando se abono a su deuda, no cuando abrio el
 * cobro: los que quedaron a medias en la pasarela no son parte de su historia.
 */
@Service
@Transactional(readOnly = true)
public class HistorialService {

    private static final Logger log = LoggerFactory.getLogger(HistorialService.class);

    private final DebtService debts;
    private final DebtEventRepository events;
    private final ObjectMapper json;

    public HistorialService(DebtService debts, DebtEventRepository events, ObjectMapper json) {
        this.debts = debts;
        this.events = events;
        this.json = json;
    }

    /** Los ultimos pagos que ve quien pregunta, del mas nuevo al mas viejo. */
    public List<PagoResponse> pagos(JwtPrincipal user) {
        List<Debt> visibles = debts.deudasVisibles(user);
        if (visibles.isEmpty()) {
            return List.of();
        }
        return events.findTop300ByDebtInAndTypeOrderByOccurredAtDesc(visibles, DebtEvent.Type.payment_applied)
                .stream().map(this::respuesta).toList();
    }

    /** Un pago, si a quien pregunta le corresponde verlo. */
    public DebtEvent pago(JwtPrincipal user, Long id) {
        DebtEvent evento = events.findById(id)
                .filter(e -> e.getType() == DebtEvent.Type.payment_applied)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
        debts.requireVisible(user, evento.getDebt().getId());
        return evento;
    }

    PagoResponse respuesta(DebtEvent evento) {
        Debt deuda = evento.getDebt();
        DetallePago detalle = detalle(evento);
        String[] referencia = partes(evento.getReference());
        return new PagoResponse(
                evento.getId(),
                deuda.getId(),
                deuda.getExternalId(),
                deuda.getCreditor().getTradeName(),
                deuda.getDebtor().getFullName(),
                deuda.getDebtor().getRut(),
                deuda.getConcept(),
                deuda.getCurrency(),
                evento.getAmount(),
                detalle == null ? null : detalle.montoClp(),
                detalle == null ? null : detalle.valorUf(),
                detalle != null && detalle.pasarela() != null ? detalle.pasarela() : referencia[0],
                referencia[1],
                detalle == null || detalle.cuotas() == null ? List.of() : detalle.cuotas(),
                detalle == null ? null : detalle.de(),
                detalle == null ? null : detalle.fuera(),
                evento.getOccurredAt());
    }

    /** El detalle guardado con el pago. Los pagos anteriores a que existiera no lo traen. */
    DetallePago detalle(DebtEvent evento) {
        if (evento.getDetail() == null || evento.getDetail().isBlank()) {
            return null;
        }
        try {
            return json.readValue(evento.getDetail(), DetallePago.class);
        } catch (Exception e) {
            log.warn("El detalle del pago {} no se pudo leer: {}", evento.getId(), e.getMessage());
            return null;
        }
    }

    /** "webpay:wp-9f31c2" -> ["webpay", "wp-9f31c2"]. */
    private static String[] partes(String referencia) {
        if (referencia == null || !referencia.contains(":")) {
            return new String[]{null, referencia};
        }
        int dos = referencia.indexOf(':');
        return new String[]{referencia.substring(0, dos).toLowerCase(), referencia.substring(dos + 1)};
    }
}
