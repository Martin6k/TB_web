package com.tbridge.debt.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.response.ConvenioEnRiesgoResponse;
import com.tbridge.debt.dto.response.CuotaPorVencerResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.InstallmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Los vencimientos del deudor y los convenios en riesgo de la empresa. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CuotaServiceTest {

    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Santiago"));

    @Mock private DebtService debts;
    @Mock private InstallmentRepository installments;

    private CuotaService servicio;
    private Organization patrimonio;
    private final JwtPrincipal deudor = new JwtPrincipal("17893456-2", null, "DEBTOR", null, "17893456-2");
    private final JwtPrincipal empresa = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");

    @BeforeEach
    void preparar() {
        servicio = new CuotaService(debts, installments);
        patrimonio = new Organization();
        patrimonio.setId(1L);
        patrimonio.setTradeName("Patrimonio Inmuebles");
    }

    private Debt deuda(long id, Debt.Status estado) {
        Debtor quien = new Debtor();
        quien.setRut("17893456-2");
        quien.setFullName("Ignacio Tapia Rojas");
        Debt deuda = new Debt();
        deuda.setId(id);
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(quien);
        deuda.setExternalId("CTR-" + id);
        deuda.setConcept("Arriendo mensual");
        deuda.setCurrency(Debt.Currency.CLP);
        deuda.setStatus(estado);
        return deuda;
    }

    private Installment cuota(Debt deuda, long id, int numero, LocalDate vence, Installment.Status estado,
                              boolean enConvenio) {
        Installment cuota = new Installment();
        cuota.setId(id);
        cuota.setDebt(deuda);
        cuota.setNumber((short) numero);
        cuota.setDueDate(vence);
        cuota.setAmount(new BigDecimal("116667"));
        cuota.setStatus(estado);
        if (estado == Installment.Status.paid) {
            cuota.setPaidAt(vence.minusDays(2).atStartOfDay(ZoneId.of("America/Santiago")).toInstant());
        }
        if (enConvenio) {
            cuota.setRepactation(new Repactation());
        }
        return cuota;
    }

    /** Un convenio de tres: la primera pagada, la segunda vencida hace 5 dias, la tercera por venir. */
    private Debt convenioConUnaVencida() {
        Debt convenio = deuda(7L, Debt.Status.repacted);
        List<Installment> cuotas = new ArrayList<>(List.of(
                cuota(convenio, 70, 1, HOY.minusDays(60), Installment.Status.void_, false),
                cuota(convenio, 71, 2, HOY.minusDays(35), Installment.Status.paid, true),
                cuota(convenio, 72, 3, HOY.minusDays(5), Installment.Status.pending, true),
                cuota(convenio, 73, 4, HOY.plusDays(25), Installment.Status.pending, true)));
        when(installments.findByDebtOrderByNumberAsc(convenio)).thenReturn(cuotas);
        return convenio;
    }

    @Test
    void los_vencimientos_van_por_fecha_con_su_lugar_en_el_plan() {
        Debt convenio = convenioConUnaVencida();
        Debt sinConvenio = deuda(8L, Debt.Status.open);
        when(installments.findByDebtOrderByNumberAsc(sinConvenio)).thenReturn(List.of(
                cuota(sinConvenio, 80, 1, HOY.minusDays(20), Installment.Status.pending, false)));
        when(debts.deudasVisibles(deudor)).thenReturn(List.of(convenio, sinConvenio));

        List<CuotaPorVencerResponse> cuotas = servicio.vencimientos(deudor);

        assertEquals(List.of(80L, 72L, 73L), cuotas.stream().map(CuotaPorVencerResponse::id).toList());
        CuotaPorVencerResponse vencida = cuotas.get(1);
        assertTrue(vencida.vencida());
        assertEquals(-5, vencida.dias());
        //  La anulada no cuenta: es la 2 de un plan de 3.
        assertEquals(2, vencida.lugar());
        assertEquals(3, vencida.deCuotas());
        assertTrue(vencida.enConvenio());
        assertFalse(cuotas.get(0).enConvenio());
        assertFalse(cuotas.get(2).vencida());
    }

    @Test
    void los_convenios_en_riesgo_son_los_que_tienen_cuotas_vencidas() {
        Debt enRiesgo = convenioConUnaVencida();
        Debt alDia = deuda(9L, Debt.Status.repacted);
        when(installments.findByDebtOrderByNumberAsc(alDia)).thenReturn(List.of(
                cuota(alDia, 90, 1, HOY.plusDays(10), Installment.Status.pending, true)));
        Debt pendiente = deuda(10L, Debt.Status.open);
        when(debts.deudasVisibles(empresa)).thenReturn(List.of(enRiesgo, alDia, pendiente));

        List<ConvenioEnRiesgoResponse> convenios = servicio.enRiesgo(empresa);

        assertEquals(1, convenios.size());
        ConvenioEnRiesgoResponse convenio = convenios.getFirst();
        assertEquals(7L, convenio.deudaId());
        assertEquals(1, convenio.cuotasVencidas());
        assertEquals(5, convenio.diasAtraso());
        assertEquals(new BigDecimal("116667"), convenio.montoVencido());
        assertEquals(1, convenio.cuotasPagadas());
        assertEquals(3, convenio.cuotasTotales());
        assertEquals(new BigDecimal("233334"), convenio.saldo());
        assertEquals(HOY.minusDays(37).atStartOfDay(ZoneId.of("America/Santiago")).toInstant(), convenio.ultimoPago());
    }

    /** Un convenio al dia, con un mes que el acreedor informo despues y que ya vencio. */
    private Debt convenioConUnMesAparte() {
        Debt convenio = deuda(7L, Debt.Status.repacted);
        when(installments.findByDebtOrderByNumberAsc(convenio)).thenReturn(List.of(
                cuota(convenio, 71, 2, HOY.plusDays(10), Installment.Status.pending, true),
                cuota(convenio, 72, 3, HOY.plusDays(40), Installment.Status.pending, true),
                cuota(convenio, 74, 4, HOY.minusDays(3), Installment.Status.pending, false)));
        return convenio;
    }

    @Test
    void un_mes_aparte_no_corre_la_numeracion_del_convenio() {
        Debt convenio = convenioConUnMesAparte();
        when(debts.deudasVisibles(deudor)).thenReturn(List.of(convenio));

        List<CuotaPorVencerResponse> cuotas = servicio.vencimientos(deudor);

        assertEquals(List.of(74L, 71L, 72L), cuotas.stream().map(CuotaPorVencerResponse::id).toList());
        CuotaPorVencerResponse aparte = cuotas.getFirst();
        assertTrue(aparte.fueraDelConvenio());
        assertFalse(aparte.enConvenio());
        assertEquals(0, aparte.lugar());
        assertEquals(1, cuotas.get(1).lugar());
        assertEquals(2, cuotas.get(1).deCuotas());
        assertFalse(cuotas.get(1).fueraDelConvenio());
    }

    @Test
    void un_mes_aparte_vencido_no_pone_en_riesgo_el_convenio() {
        Debt convenio = convenioConUnMesAparte();
        when(debts.deudasVisibles(empresa)).thenReturn(List.of(convenio));

        assertTrue(servicio.enRiesgo(empresa).isEmpty());
    }

    @Test
    void cada_quien_ve_lo_suyo() {
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> servicio.vencimientos(empresa)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> servicio.enRiesgo(deudor)).getStatus());
    }

    @Test
    void una_deuda_pagada_o_retirada_no_tiene_vencimientos() {
        when(debts.deudasVisibles(any())).thenReturn(List.of(deuda(11L, Debt.Status.paid),
                deuda(12L, Debt.Status.withdrawn)));

        assertTrue(servicio.vencimientos(deudor).isEmpty());
    }
}
