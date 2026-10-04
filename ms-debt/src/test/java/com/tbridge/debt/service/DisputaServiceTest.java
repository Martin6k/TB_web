package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.evento.DeudaDisputadaDatos;
import com.tbridge.debt.dto.evento.DeudaReanudadaDatos;
import com.tbridge.debt.dto.evento.DeudaRetiradaDatos;
import com.tbridge.debt.dto.request.DisputaRequest;
import com.tbridge.debt.dto.request.ResolucionDisputaRequest;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La disputa: el deudor la abre con un motivo, y la empresa que cobra la
 * resuelve, reanudando el cobro o retirando la deuda.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputaServiceTest {

    private static final JwtPrincipal DEUDOR = new JwtPrincipal("13579246-2", null, "DEBTOR", null, "13579246-2");
    private static final JwtPrincipal EMPRESA =
            new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");

    @Mock private DebtService deudas;
    @Mock private DebtRepository debts;
    @Mock private InstallmentRepository installments;
    @Mock private DebtEventRepository events;
    @Mock private EventosService eventos;

    private DisputaService servicio;
    private Debt deuda;
    private final List<Installment> pendientes = new ArrayList<>();

    @BeforeEach
    void preparar() {
        servicio = new DisputaService(deudas, debts, installments, events, eventos, new ObjectMapper());
        deuda = new Debt();
        deuda.setId(7L);
        deuda.setExternalId("SN-2026-118");
        deuda.setStatus(Debt.Status.open);
        when(deudas.requireVisible(any(), eq(7L))).thenReturn(deuda);
        when(installments.findByDebtAndStatus(deuda, Installment.Status.pending)).thenReturn(pendientes);
    }

    private static Installment cuota(Repactation plan) {
        Installment cuota = new Installment();
        cuota.setAmount(new BigDecimal("241666"));
        cuota.setDueDate(LocalDate.of(2026, 10, 21));
        cuota.setStatus(Installment.Status.pending);
        cuota.setRepactation(plan);
        return cuota;
    }

    @Test
    void el_deudor_disputa_con_un_motivo_y_la_cadena_recibe_solo_el_motivo() {
        servicio.disputar(DEUDOR, 7L, new DisputaRequest("ya_pagada", "Pague en la clinica el 10 de agosto"));

        assertEquals(Debt.Status.disputed, deuda.getStatus());
        ArgumentCaptor<DebtEvent> evento = ArgumentCaptor.forClass(DebtEvent.class);
        verify(events).save(evento.capture());
        assertEquals(DebtEvent.Type.disputed, evento.getValue().getType());
        assertEquals("ya_pagada", evento.getValue().getReference());
        assertTrue(evento.getValue().getDetail().contains("Pague en la clinica"), "lo que escribio queda en DataBridge");
        //  El aviso no lleva lo que escribio el deudor: los eventos no llevan datos personales.
        verify(eventos).publicar(eq(deuda), eq(EventosService.DEUDA_DISPUTADA),
                eq(new DeudaDisputadaDatos("SN-2026-118", "ya_pagada")), any());
    }

    @Test
    void un_motivo_que_no_existe_no_abre_nada() {
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.disputar(DEUDOR, 7L, new DisputaRequest("porque_si", null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals(Debt.Status.open, deuda.getStatus());
    }

    @Test
    void no_se_disputa_lo_pagado_ni_dos_veces_lo_mismo() {
        deuda.setStatus(Debt.Status.paid);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> servicio.disputar(DEUDOR, 7L, new DisputaRequest("otro", null))).getStatus());

        deuda.setStatus(Debt.Status.disputed);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> servicio.disputar(DEUDOR, 7L, new DisputaRequest("otro", null))).getStatus());
        verify(eventos, never()).publicar(any(), any(), any(), any());
    }

    @Test
    void la_empresa_no_disputa_y_el_deudor_no_resuelve() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> servicio.disputar(EMPRESA, 7L, new DisputaRequest("otro", null))).getStatus());
        deuda.setStatus(Debt.Status.disputed);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> servicio.resolver(DEUDOR, 7L, new ResolucionDisputaRequest("retirar", null))).getStatus());
    }

    @Test
    void reanudar_la_devuelve_a_cobranza_y_a_su_convenio_si_tenia() {
        deuda.setStatus(Debt.Status.disputed);
        pendientes.add(cuota(new Repactation()));

        servicio.resolver(EMPRESA, 7L, new ResolucionDisputaRequest("reanudar", "El pago no aparece"));

        assertEquals(Debt.Status.repacted, deuda.getStatus());
        verify(eventos).publicar(eq(deuda), eq(EventosService.DEUDA_REANUDADA),
                eq(new DeudaReanudadaDatos("SN-2026-118", "disputa_rechazada", true)), any());
    }

    @Test
    void reanudar_sin_convenio_la_deja_en_gestion() {
        deuda.setStatus(Debt.Status.disputed);
        pendientes.add(cuota(null));

        servicio.resolver(EMPRESA, 7L, new ResolucionDisputaRequest("reanudar", null));

        assertEquals(Debt.Status.open, deuda.getStatus());
    }

    @Test
    void retirar_anula_lo_pendiente_con_el_motivo_disputa_resuelta() {
        deuda.setStatus(Debt.Status.disputed);
        Installment pendiente = cuota(null);
        pendientes.add(pendiente);

        servicio.resolver(EMPRESA, 7L, new ResolucionDisputaRequest("retirar", null));

        assertEquals(Debt.Status.withdrawn, deuda.getStatus());
        verify(events).save(org.mockito.ArgumentMatchers.argThat(e -> e.getType() == DebtEvent.Type.withdrawn
                && "disputa_resuelta".equals(e.getReference())));
        assertEquals(Installment.Status.void_, pendiente.getStatus());
        verify(eventos).publicar(eq(deuda), eq(EventosService.DEUDA_RETIRADA),
                eq(new DeudaRetiradaDatos("SN-2026-118", "disputa_resuelta")), any());
    }

    @Test
    void solo_se_resuelve_lo_que_esta_en_disputa() {
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.resolver(EMPRESA, 7L, new ResolucionDisputaRequest("retirar", null)));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertFalse(deuda.getStatus() == Debt.Status.withdrawn);
    }
}
