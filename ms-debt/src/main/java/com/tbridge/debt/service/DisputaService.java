package com.tbridge.debt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.evento.DeudaDisputadaDatos;
import com.tbridge.debt.dto.evento.DeudaReanudadaDatos;
import com.tbridge.debt.dto.evento.DeudaRetiradaDatos;
import com.tbridge.debt.dto.request.DisputaRequest;
import com.tbridge.debt.dto.request.ResolucionDisputaRequest;
import com.tbridge.debt.dto.response.DebtDetailResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * La disputa: el deudor dice que una deuda no es suya o no corresponde, y la
 * empresa que cobra la revisa.
 *
 * <ol>
 *   <li><b>El deudor la disputa</b> desde el portal, con un motivo. Mientras se
 *       revisa la deuda queda <i>en disputa</i>: no se puede pagar ni repactar,
 *       y no le llegan recordatorios. Se avisa {@code deuda.disputada}.</li>
 *   <li><b>La empresa la resuelve</b> desde su portal. Si la deuda corresponde,
 *       la <i>reanuda</i>: vuelve a cobranza, con su convenio si tenia uno, y se
 *       avisa {@code deuda.reanudada}. Si no corresponde, la <i>retira</i> con
 *       el motivo {@code disputa_resuelta}, y se avisa {@code deuda.retirada}.</li>
 * </ol>
 *
 * <p>Los avisos viajan por la cadena hasta el acreedor, que ve el contrato en
 * disputa en su propio sistema.
 */
@Service
@Transactional
public class DisputaService {

    /** Los motivos del contrato (seccion 8.2): lo que entienden todos los sistemas. */
    public static final Set<String> MOTIVOS = Set.of("no_reconoce", "ya_pagada", "monto_incorrecto", "otro");

    private final DebtService deudas;
    private final DebtRepository debts;
    private final InstallmentRepository installments;
    private final DebtEventRepository events;
    private final EventosService eventos;
    private final ObjectMapper json;

    public DisputaService(DebtService deudas, DebtRepository debts, InstallmentRepository installments,
                          DebtEventRepository events, EventosService eventos, ObjectMapper json) {
        this.deudas = deudas;
        this.debts = debts;
        this.installments = installments;
        this.events = events;
        this.eventos = eventos;
        this.json = json;
    }

    /** El deudor no reconoce la deuda. */
    public DebtDetailResponse disputar(JwtPrincipal user, Long id, DisputaRequest pedido) {
        if (user != null && user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La disputa la abre el deudor");
        }
        Debt deuda = deudas.requireVisible(user, id);
        String motivo = pedido.motivo() == null ? "" : pedido.motivo().trim();
        if (!MOTIVOS.contains(motivo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ese motivo no existe");
        }
        switch (deuda.getStatus()) {
            case open, repacted -> { }
            case disputed -> throw new ApiException(HttpStatus.CONFLICT, "Esa deuda ya esta en revision");
            case paid -> throw new ApiException(HttpStatus.CONFLICT, "Esa deuda ya esta pagada");
            case withdrawn -> throw new ApiException(HttpStatus.CONFLICT, "Esa deuda ya no esta en cobranza");
        }

        deuda.setStatus(Debt.Status.disputed);
        deuda.setUpdatedAt(Instant.now());
        debts.save(deuda);
        String detalle = pedido.detalle() == null || pedido.detalle().isBlank() ? null : pedido.detalle().trim();
        events.save(DebtEvent.de(deuda, DebtEvent.Type.disputed, DebtEvent.Actor.debtor)
                .conReferencia(motivo)
                .conDetalle(detalle == null ? null : comoJson(Map.of("detalle", detalle))));
        eventos.publicar(deuda, EventosService.DEUDA_DISPUTADA,
                new DeudaDisputadaDatos(deuda.getExternalId(), motivo), Instant.now());
        return deudas.getFor(user, id);
    }

    /** La empresa que cobra revisa la disputa: la deuda corresponde (reanudar) o no (retirar). */
    public DebtDetailResponse resolver(JwtPrincipal user, Long id, ResolucionDisputaRequest pedido) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La disputa la resuelve la empresa que cobra");
        }
        Debt deuda = deudas.requireVisible(user, id);
        if (deuda.getStatus() != Debt.Status.disputed) {
            throw new ApiException(HttpStatus.CONFLICT, "Esa deuda no esta en disputa");
        }
        String resultado = pedido.resultado() == null ? "" : pedido.resultado().trim();
        String nota = pedido.nota() == null || pedido.nota().isBlank() ? null : pedido.nota().trim();
        Map<String, String> detalle = new LinkedHashMap<>();
        if (nota != null) {
            detalle.put("nota", nota);
        }

        switch (resultado) {
            case "reanudar" -> reanudar(deuda, detalle);
            case "retirar" -> retirar(deuda, detalle);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "La disputa se reanuda o se retira");
        }
        return deudas.getFor(user, id);
    }

    private void reanudar(Debt deuda, Map<String, String> detalle) {
        //  Vuelve como estaba antes de la disputa: con su convenio si le quedan
        //  cuotas del plan, o en gestion.
        boolean conConvenio = installments.findByDebtAndStatus(deuda, Installment.Status.pending).stream()
                .anyMatch(Installment::enConvenio);
        deuda.setStatus(conConvenio ? Debt.Status.repacted : Debt.Status.open);
        deuda.setUpdatedAt(Instant.now());
        debts.save(deuda);
        detalle.put("resultado", "reanudada");
        events.save(DebtEvent.de(deuda, DebtEvent.Type.updated, DebtEvent.Actor.agency)
                .conReferencia("disputa_rechazada")
                .conDetalle(comoJson(detalle)));
        eventos.publicar(deuda, EventosService.DEUDA_REANUDADA,
                new DeudaReanudadaDatos(deuda.getExternalId(), "disputa_rechazada", conConvenio), Instant.now());
    }

    private void retirar(Debt deuda, Map<String, String> detalle) {
        deuda.setStatus(Debt.Status.withdrawn);
        deuda.setWithdrawnReason("disputa_resuelta");
        deuda.setUpdatedAt(Instant.now());
        debts.save(deuda);
        //  Retirar no borra: las cuotas pendientes se anulan, y queda el rastro.
        for (Installment cuota : installments.findByDebtAndStatus(deuda, Installment.Status.pending)) {
            cuota.setStatus(Installment.Status.void_);
            installments.save(cuota);
        }
        events.save(DebtEvent.de(deuda, DebtEvent.Type.withdrawn, DebtEvent.Actor.agency)
                .conReferencia("disputa_resuelta")
                .conDetalle(detalle.isEmpty() ? null : comoJson(detalle)));
        eventos.publicar(deuda, EventosService.DEUDA_RETIRADA,
                new DeudaRetiradaDatos(deuda.getExternalId(), "disputa_resuelta"), Instant.now());
    }

    private String comoJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo escribir el detalle de la disputa", e);
        }
    }
}
