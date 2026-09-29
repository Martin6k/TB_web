package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.events.PagoConfirmado;
import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.response.DebtSnapshotResponse;
import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.repository.RepactationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las reglas de las deudas: cada quien ve lo suyo, el saldo sale de las
 * cuotas, y un pago avisado dos veces se abona una.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DebtServiceTest {

    @Mock private DebtRepository debts;
    @Mock private DebtorRepository debtors;
    @Mock private OrganizationRepository organizations;
    @Mock private DebtChargeRepository charges;
    @Mock private InstallmentRepository installments;
    @Mock private RepactationRepository repactations;
    @Mock private DebtEventRepository events;
    @Mock private EventosService eventos;
    @Spy private RepactationService repactation = new RepactationService();
    @Spy private ObjectMapper json = new ObjectMapper();

    @InjectMocks
    private DebtService servicio;

    private Organization patrimonio;
    private Organization apofyx;
    private Debtor valentina;
    private Debt deuda;
    private final List<Installment> cuotas = new ArrayList<>();
    private final List<DebtEvent> historia = new ArrayList<>();

    private static Organization organizacion(long id, String rut, String nombre) {
        Organization org = new Organization();
        org.setId(id);
        org.setRut(rut);
        org.setTradeName(nombre);
        return org;
    }

    @BeforeEach
    void preparar() {
        patrimonio = organizacion(1L, "76418902-7", "Patrimonio Inmuebles");
        apofyx = organizacion(2L, "77305118-6", "APOFYX");
        valentina = new Debtor();
        valentina.setId(10L);
        valentina.setRut("18905214-6");
        valentina.setFullName("Valentina Soto Pizarro");

        Batch lote = new Batch();
        lote.setSender(apofyx);
        lote.setExternalId("APX-2026-09-19-004");
        deuda = new Debt();
        deuda.setId(3L);
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(valentina);
        deuda.setLastBatch(lote);
        deuda.setExternalId("CTR-2026-031");
        deuda.setConcept("Arriendo mensual");
        deuda.setCurrency(Debt.Currency.CLP);
        deuda.setOriginalAmount(new BigDecimal("410000"));

        Installment unica = new Installment();
        unica.setId(12L);
        unica.setDebt(deuda);
        unica.setNumber((short) 1);
        unica.setDueDate(LocalDate.of(2026, 9, 5));
        unica.setAmount(new BigDecimal("410000"));
        cuotas.add(unica);

        when(debts.findById(3L)).thenReturn(Optional.of(deuda));
        when(installments.findById(any())).thenAnswer(llamada -> cuotas.stream()
                .filter(c -> c.getId().equals(llamada.getArgument(0))).findFirst());
        when(installments.findByDebtOrderByNumberAsc(deuda)).thenReturn(cuotas);
        when(installments.findByDebtAndStatus(eq(deuda), any())).thenAnswer(llamada -> cuotas.stream()
                .filter(c -> c.getStatus() == llamada.getArgument(1)).toList());
        when(events.findByDebtOrderByOccurredAtAsc(deuda)).thenReturn(historia);
        when(events.save(any())).thenAnswer(llamada -> {
            historia.add(llamada.getArgument(0));
            return llamada.getArgument(0);
        });
        when(debtors.findByRut("18905214-6")).thenReturn(Optional.of(valentina));
        when(organizations.findByRut("77305118-6")).thenReturn(Optional.of(apofyx));
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));
    }

    private static JwtPrincipal deudor(String rut) {
        return new JwtPrincipal(rut, null, "DEBTOR", null, rut);
    }

    private static JwtPrincipal empresa(String rut) {
        return new JwtPrincipal("1", "x@empresa.cl", "CREDITOR", "Alguien", rut);
    }

    @Test
    void el_deudor_ve_su_deuda_con_el_saldo_de_las_cuotas() {
        when(debts.findByDebtorOrderByUpdatedAtDesc(valentina)).thenReturn(List.of(deuda));

        List<DebtSummaryResponse> suyas = servicio.listFor(deudor("18905214-6"));

        assertEquals(1, suyas.size());
        assertEquals(new BigDecimal("410000"), suyas.getFirst().saldo());
        assertEquals(BigDecimal.ZERO, suyas.getFirst().pagado());
        assertEquals(0, suyas.getFirst().cuotasPagadas());
        assertEquals(1, suyas.getFirst().cuotasTotales());
        assertEquals(false, suyas.getFirst().conConvenio());
    }

    /** Pasa la deuda a un convenio de tres cuotas de 140.000, la 12 anulada. */
    private void enConvenioDeTres() {
        cuotas.getFirst().setStatus(Installment.Status.void_);
        Repactation convenio = new Repactation();
        for (long id = 13; id <= 15; id++) {
            Installment cuota = new Installment();
            cuota.setId(id);
            cuota.setDebt(deuda);
            cuota.setRepactation(convenio);
            cuota.setNumber((short) (id - 11));
            cuota.setDueDate(LocalDate.of(2026, 10, 5).plusMonths(id - 13));
            cuota.setAmount(new BigDecimal("140000"));
            cuotas.add(cuota);
        }
        deuda.setStatus(Debt.Status.repacted);
    }

    @Test
    void el_avance_cuenta_las_cuotas_vigentes_y_no_las_anuladas() {
        enConvenioDeTres();
        cuotas.get(1).setStatus(Installment.Status.paid);
        when(debts.findByDebtorOrderByUpdatedAtDesc(valentina)).thenReturn(List.of(deuda));

        DebtSummaryResponse resumen = servicio.listFor(deudor("18905214-6")).getFirst();

        assertEquals(1, resumen.cuotasPagadas());
        assertEquals(3, resumen.cuotasTotales());
        assertEquals(new BigDecimal("280000"), resumen.saldo());
        assertEquals(true, resumen.conConvenio());
    }

    @Test
    void varias_cuotas_se_cobran_juntas_si_son_las_que_vencen_primero() {
        enConvenioDeTres();

        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, List.of(14L, 13L));

        assertEquals(new BigDecimal("280000"), snapshot.amount());
        //  Con varias no va ninguna: el pago se imputa de la mas antigua a la mas nueva.
        assertEquals(null, snapshot.installmentId());
    }

    @Test
    void no_se_salta_una_cuota_vieja_para_pagar_una_nueva() {
        enConvenioDeTres();

        ApiException error = assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(14L)));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("Las cuotas se pagan en orden, desde la que vence primero", error.getMessage());
    }

    @Test
    void una_cuota_anulada_o_repetida_no_se_cobra() {
        enConvenioDeTres();

        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(12L))).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(13L, 13L))).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(99L))).getStatus());
    }

    @Test
    void pagar_el_saldo_de_un_convenio_salda_todas_las_cuotas() {
        //  Antes el cobro del saldo llevaba la id de la primera cuota, y al
        //  confirmarse solo esa quedaba pagada.
        enConvenioDeTres();
        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, null);
        assertEquals(new BigDecimal("420000"), snapshot.amount());
        assertEquals(null, snapshot.installmentId());

        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 42L, 3L, snapshot.installmentId(),
                "18905214-6", "76418902-7", snapshot.amount(), "CLP", 420000L, null, "webpay", "wp-convenio",
                Instant.now()));

        assertEquals(Debt.Status.paid, deuda.getStatus());
    }

    @Test
    void el_pago_guarda_que_cuotas_cubrio_por_donde_y_cuando() throws Exception {
        enConvenioDeTres();
        Instant pagadoEn = Instant.parse("2026-09-23T15:30:00Z");

        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 43L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("280000"), "CLP", 280000L, null, "WEBPAY", "wp-dos", pagadoEn));

        DebtEvent aplicado = historia.stream()
                .filter(e -> e.getType() == DebtEvent.Type.payment_applied).findFirst().orElseThrow();
        JsonNode detalle = json.readTree(aplicado.getDetail());
        //  La anulada (la 12) no cuenta: el plan es de tres, y se pagaron la 1 y la 2.
        assertEquals("[1,2]", detalle.get("cuotas").toString());
        assertEquals(3, detalle.get("de").asInt());
        assertEquals("webpay", detalle.get("pasarela").asText());
        assertEquals(43, detalle.get("pago_id").asInt());
        assertEquals(280000, detalle.get("monto_clp").asInt());
        //  Fechado cuando pago el deudor, no cuando llego el aviso.
        assertEquals(pagadoEn, aplicado.getOccurredAt());
    }

    /** Un mes que el acreedor informo despues del convenio: una cuota aparte, la que vence primero. */
    private void conUnMesAparte() {
        Installment aparte = new Installment();
        aparte.setId(16L);
        aparte.setDebt(deuda);
        aparte.setNumber((short) 5);
        aparte.setDueDate(LocalDate.of(2026, 10, 1));
        aparte.setAmount(new BigDecimal("410000"));
        cuotas.add(aparte);
    }

    @Test
    void el_mes_aparte_no_cuenta_en_el_avance_del_convenio() {
        enConvenioDeTres();
        cuotas.get(1).setStatus(Installment.Status.paid);
        conUnMesAparte();
        when(debts.findByDebtorOrderByUpdatedAtDesc(valentina)).thenReturn(List.of(deuda));

        DebtSummaryResponse resumen = servicio.listFor(deudor("18905214-6")).getFirst();

        assertEquals(1, resumen.cuotasPagadas());
        assertEquals(3, resumen.cuotasTotales());
        assertEquals(new BigDecimal("690000"), resumen.saldo());
        //  Lo pagado es lo que se pago aca, no el monto original menos el saldo.
        assertEquals(new BigDecimal("140000"), resumen.pagado());
    }

    @Test
    void un_pago_que_cubre_el_mes_aparte_lo_anota_fuera_del_convenio() throws Exception {
        enConvenioDeTres();
        conUnMesAparte();

        //  El mes aparte vence primero: se paga el y la primera del convenio.
        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 44L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("550000"), "CLP", 550000L, null, "webpay", "wp-aparte", Instant.now()));

        JsonNode detalle = json.readTree(historia.stream()
                .filter(e -> e.getType() == DebtEvent.Type.payment_applied).findFirst().orElseThrow().getDetail());
        assertEquals("[1]", detalle.get("cuotas").toString());
        assertEquals(3, detalle.get("de").asInt());
        assertEquals(1, detalle.get("fuera").asInt());
    }

    @Test
    void dos_cuotas_pagadas_juntas_dejan_la_tercera_pendiente() {
        enConvenioDeTres();

        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 43L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("280000"), "CLP", 280000L, null, "webpay", "wp-dos", Instant.now()));

        assertEquals(List.of(Installment.Status.void_, Installment.Status.paid, Installment.Status.paid,
                Installment.Status.pending), cuotas.stream().map(Installment::getStatus).toList());
        assertEquals(Debt.Status.repacted, deuda.getStatus());
    }

    @Test
    void la_agencia_que_entrego_la_cartera_la_ve_y_otra_empresa_no() {
        //  APOFYX no es la acreedora, pero envio el lote: opera esa cartera.
        assertEquals(3L, servicio.requireVisible(empresa("77305118-6"), 3L).getId());

        Organization otra = organizacion(9L, "96512345-8", "Otra");
        when(organizations.findByRut("96512345-8")).thenReturn(Optional.of(otra));
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.requireVisible(empresa("96512345-8"), 3L));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void un_deudor_no_ve_la_deuda_de_otro() {
        Debtor felipe = new Debtor();
        felipe.setId(11L);
        when(debtors.findByRut("16482337-7")).thenReturn(Optional.of(felipe));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.getFor(deudor("16482337-7"), 3L));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void una_sesion_de_empresa_sin_rut_no_ve_ninguna_cartera() {
        //  Devolver "todas" seria la fuga que este diseno cerro.
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.listFor(new JwtPrincipal("1", "x@y.cl", "CREDITOR", "X", null)));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void ms_payments_cobra_el_saldo_y_la_cuota_que_vence_primero() {
        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, null);

        assertEquals(new BigDecimal("410000"), snapshot.amount());
        assertEquals(12L, snapshot.installmentId());
        assertEquals("18905214-6", snapshot.debtorRut());
    }

    private PagoConfirmado aviso() {
        return new PagoConfirmado(PagoConfirmado.TIPO, 41L, 3L, null, "18905214-6", "76418902-7",
                new BigDecimal("410000"), "CLP", 410000L, null, "webpay", "wp-9f31c2", Instant.now());
    }

    @Test
    void un_pago_que_cubre_el_saldo_deja_la_deuda_pagada_y_avisa() {
        servicio.onPagoConfirmado(aviso());

        assertEquals(Installment.Status.paid, cuotas.getFirst().getStatus());
        assertEquals(Debt.Status.paid, deuda.getStatus());
        verify(eventos).publicar(eq(deuda), eq(EventosService.PAGO_CONFIRMADO), any(), any());
        verify(eventos).publicar(eq(deuda), eq(EventosService.DEUDA_SALDADA), any(), any());
    }

    @Test
    void el_mismo_aviso_dos_veces_se_abona_una_sola() {
        servicio.onPagoConfirmado(aviso());
        servicio.onPagoConfirmado(aviso());

        ArgumentCaptor<DebtEvent> anotados = ArgumentCaptor.forClass(DebtEvent.class);
        verify(events, org.mockito.Mockito.times(2)).save(anotados.capture());
        //  payment_applied y settled, de la primera vez. La segunda no anota nada.
        assertEquals(List.of(DebtEvent.Type.payment_applied, DebtEvent.Type.settled),
                anotados.getAllValues().stream().map(DebtEvent::getType).toList());
    }

    @Test
    void la_empresa_no_repacta_por_el_deudor() {
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.applyRepact(empresa("77305118-6"), 3L, 6));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(installments, never()).save(any());
    }

    @Test
    void repactar_anula_las_cuotas_pendientes_en_vez_de_borrarlas() {
        when(installments.findByDebtOrderByNumberAsc(deuda)).thenReturn(cuotas);
        when(installments.save(any())).thenAnswer(llamada -> llamada.getArgument(0));

        servicio.applyRepact(deudor("18905214-6"), 3L, 3);

        assertEquals(Installment.Status.void_, cuotas.getFirst().getStatus());
        assertEquals(Debt.Status.repacted, deuda.getStatus());
        verify(eventos).publicar(eq(deuda), eq(EventosService.REPACTACION_ACEPTADA), any(), any());
    }
}
