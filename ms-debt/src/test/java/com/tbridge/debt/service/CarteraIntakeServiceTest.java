package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.CarteraResponse.ResultadoDeuda;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtCharge;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.BatchRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El alcance de DataBridge: solo deudores morosos. Una deuda entra con al
 * menos dos meses impagos; una que ya esta en gestion puede volver con menos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarteraIntakeServiceTest {

    @Mock private OrganizationRepository organizations;
    @Mock private MandateRepository mandates;
    @Mock private CampaignRepository campaigns;
    @Mock private BatchRepository batches;
    @Mock private DebtorRepository debtors;
    @Mock private DebtRepository debts;
    @Mock private DebtChargeRepository charges;
    @Mock private InstallmentRepository installments;
    @Mock private DebtEventRepository events;
    @Mock private EventosService eventos;
    @Mock private ApplicationEventPublisher avisos;

    private final ObjectMapper json = new ObjectMapper();
    private CarteraIntakeService servicio;
    private Organization patrimonio;

    @BeforeEach
    void preparar() {
        servicio = new CarteraIntakeService(organizations, mandates, campaigns, batches, debtors, debts,
                charges, installments, events, json, eventos, avisos, 2);

        //  Patrimonio entrega lo suyo: sin agencia, no hace falta mandato.
        patrimonio = new Organization();
        patrimonio.setId(1L);
        patrimonio.setRut("76418902-7");
        patrimonio.setTradeName("Patrimonio Inmuebles");
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));
        when(batches.findBySenderAndExternalId(any(), any())).thenReturn(Optional.empty());
        when(debts.findByCreditorAndExternalId(any(), any())).thenReturn(Optional.empty());
        when(debtors.findByRut(any())).thenReturn(Optional.empty());
        when(debtors.save(any(Debtor.class))).thenAnswer(llamada -> llamada.getArgument(0));
    }

    private JsonNode cartera(String... cargos) throws Exception {
        return carteraAl("2026-09-18", cargos);
    }

    private JsonNode carteraAl(String corte, String... cargos) throws Exception {
        return json.readTree("""
                {"version": "1.0",
                 "lote": {"id_externo": "PAT-%s-01", "fecha_corte": "%s",
                          "acreedor": {"rut": "76418902-7"}},
                 "deudas": [{"id_externo": "CTR-2026-031", "moneda": "CLP", "concepto": "Arriendo mensual",
                             "deudor": {"rut": "18905214-6", "tipo": "persona",
                                        "nombre": "Valentina Soto Pizarro",
                                        "correo": "valentina.soto@correo.cl"},
                             "cargos": [%s]}]}
                """.formatted(corte, corte, String.join(",", cargos)));
    }

    private static String cargo(String concepto, String periodo, String vence) {
        return cargo(concepto, periodo, vence, 410000);
    }

    private static String cargo(String concepto, String periodo, String vence, long monto) {
        return """
                {"concepto": "%s", "periodo": "%s", "monto": %d, "fecha_vencimiento": "%s"}"""
                .formatted(concepto, periodo, monto, vence);
    }

    private ResultadoDeuda recibir(JsonNode payload) {
        CarteraResponse respuesta = servicio.recibir(patrimonio, payload, Batch.Source.api);
        return respuesta.resultados().getFirst();
    }

    @Test
    void con_un_solo_mes_impago_todavia_no_es_morosa_y_no_entra() throws Exception {
        ResultadoDeuda resultado = recibir(cartera(cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("bajo_umbral_mora", resultado.errores().getFirst().codigo());
        verify(debts, never()).save(any());
    }

    @Test
    void con_dos_meses_impagos_entra_a_cobranza() throws Exception {
        ResultadoDeuda resultado = recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        assertEquals("registrada", resultado.resultado());
        assertEquals(44L, resultado.moraDias());
    }

    @Test
    void el_arriendo_y_el_gasto_comun_del_mismo_mes_son_un_solo_mes() throws Exception {
        ResultadoDeuda resultado = recibir(cartera(
                cargo("Arriendo septiembre", "2026-09", "2026-09-05"),
                cargo("Gasto comun septiembre", "2026-09", "2026-09-10")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("bajo_umbral_mora", resultado.errores().getFirst().codigo());
    }

    @Test
    void una_deuda_en_gestion_puede_volver_con_un_solo_mes() throws Exception {
        //  El arrendatario pago agosto en la oficina y Patrimonio manda el
        //  saldo menor. Rechazarlo dejaria a DataBridge cobrando lo que ya no
        //  se debe.
        Debt enGestion = new Debt();
        enGestion.setId(3L);
        enGestion.setCreditor(patrimonio);
        enGestion.setExternalId("CTR-2026-031");
        when(debts.findByCreditorAndExternalId(patrimonio, "CTR-2026-031")).thenReturn(Optional.of(enGestion));

        ResultadoDeuda resultado = recibir(cartera(cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        assertEquals("actualizada", resultado.resultado());
    }

    // ------------------------------------------------------------------
    //  El mes siguiente: la deuda vuelve en la cartera del acreedor
    // ------------------------------------------------------------------

    private Debt existente(Debt.Status estado) {
        Debt deuda = new Debt();
        deuda.setId(3L);
        deuda.setCreditor(patrimonio);
        deuda.setExternalId("CTR-2026-031");
        deuda.setCurrency(Debt.Currency.CLP);
        deuda.setStatus(estado);
        when(debts.findByCreditorAndExternalId(patrimonio, "CTR-2026-031")).thenReturn(Optional.of(deuda));
        return deuda;
    }

    /** Un convenio sin pagar: una cuota por monto, cada una un mes despues de la anterior. */
    private List<Installment> convenio(Debt deuda, long... montos) {
        List<Installment> todas = new ArrayList<>();
        Installment original = cuota(deuda, 1, LocalDate.of(2026, 9, 5), 820000, null);
        original.setStatus(Installment.Status.void_);
        todas.add(original);
        Repactation plan = new Repactation();
        for (int i = 0; i < montos.length; i++) {
            todas.add(cuota(deuda, i + 2, LocalDate.of(2026, 10, 20).plusMonths(i), montos[i], plan));
        }
        when(installments.findByDebtOrderByNumberAsc(deuda)).thenReturn(todas);
        when(installments.findByDebtAndStatus(deuda, Installment.Status.pending))
                .thenReturn(todas.stream().filter(c -> c.getStatus() == Installment.Status.pending).toList());
        return todas.subList(1, todas.size());
    }

    private static Installment cuota(Debt deuda, int numero, LocalDate vence, long monto, Repactation plan) {
        Installment cuota = new Installment();
        cuota.setId((long) numero);
        cuota.setDebt(deuda);
        cuota.setNumber((short) numero);
        cuota.setDueDate(vence);
        cuota.setAmount(BigDecimal.valueOf(monto));
        cuota.setRepactation(plan);
        return cuota;
    }

    /** Las cuotas nuevas que se guardaron: las que no existian. */
    private List<Installment> cuotasNuevas() {
        ArgumentCaptor<Installment> guardadas = ArgumentCaptor.forClass(Installment.class);
        verify(installments, atLeastOnce()).save(guardadas.capture());
        return guardadas.getAllValues().stream().filter(c -> c.getId() == null).toList();
    }

    @Test
    void un_convenio_sigue_en_pie_y_el_mes_nuevo_queda_aparte() throws Exception {
        //  Valentina acepto 2 cuotas por agosto y septiembre. La cartera de
        //  octubre trae los dos meses y el nuevo.
        Debt deuda = existente(Debt.Status.repacted);
        List<Installment> plan = convenio(deuda, 410000, 410000);

        ResultadoDeuda resultado = recibir(carteraAl("2026-10-18",
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05"),
                cargo("Arriendo octubre", "2026-10", "2026-10-05")));

        assertEquals("actualizada", resultado.resultado());
        assertEquals(Debt.Status.repacted, deuda.getStatus());
        assertTrue(plan.stream().allMatch(c -> c.getStatus() == Installment.Status.pending
                && c.getAmount().compareTo(BigDecimal.valueOf(410000)) == 0), "las cuotas del convenio no cambian");
        Installment aparte = cuotasNuevas().getFirst();
        assertEquals(0, aparte.getAmount().compareTo(BigDecimal.valueOf(410000)));
        assertEquals(LocalDate.of(2026, 10, 5), aparte.getDueDate());
        assertEquals((short) 4, aparte.getNumber());
        assertTrue(!aparte.enConvenio(), "el mes nuevo no es parte del convenio");
    }

    @Test
    void lo_que_se_pago_en_la_oficina_se_descuenta_de_las_ultimas_cuotas() throws Exception {
        //  Tres cuotas de 410.000 y el acreedor ahora informa 590.000: pagaron
        //  640.000 en la oficina. Se va la ultima entera y 230.000 de la segunda.
        Debt deuda = existente(Debt.Status.repacted);
        List<Installment> plan = convenio(deuda, 410000, 410000, 410000);

        ResultadoDeuda resultado = recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05", 180000),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05", 410000)));

        assertEquals("actualizada", resultado.resultado());
        assertEquals(Installment.Status.pending, plan.get(0).getStatus());
        assertEquals(0, plan.get(0).getAmount().compareTo(BigDecimal.valueOf(410000)));
        assertEquals(0, plan.get(1).getAmount().compareTo(BigDecimal.valueOf(180000)));
        assertEquals(Installment.Status.void_, plan.get(2).getStatus());
        assertTrue(cuotasNuevas().isEmpty(), "no hay nada aparte que cobrar");
    }

    private void pagadaHastaSeptiembre(Debt deuda) {
        DebtCharge agosto = new DebtCharge();
        agosto.setConcept("Arriendo agosto");
        agosto.setAmount(BigDecimal.valueOf(410000));
        agosto.setDueDate(LocalDate.of(2026, 8, 5));
        DebtCharge septiembre = new DebtCharge();
        septiembre.setConcept("Arriendo septiembre");
        septiembre.setAmount(BigDecimal.valueOf(410000));
        septiembre.setDueDate(LocalDate.of(2026, 9, 5));
        when(charges.findByDebtOrderByDueDateAsc(deuda)).thenReturn(List.of(agosto, septiembre));
    }

    @Test
    void una_deuda_pagada_vuelve_si_el_deudor_se_atrasa_de_nuevo() throws Exception {
        Debt deuda = existente(Debt.Status.paid);
        pagadaHastaSeptiembre(deuda);

        ResultadoDeuda resultado = recibir(carteraAl("2026-11-18",
                cargo("Arriendo octubre", "2026-10", "2026-10-05"),
                cargo("Arriendo noviembre", "2026-11", "2026-11-05")));

        assertEquals("actualizada", resultado.resultado());
        assertEquals(Debt.Status.open, deuda.getStatus());
        assertEquals(0, cuotasNuevas().getFirst().getAmount().compareTo(BigDecimal.valueOf(820000)));
    }

    @Test
    void una_deuda_pagada_no_vuelve_con_lo_que_ya_se_pago() throws Exception {
        //  Septiembre ya se pago: cobrarlo de nuevo seria cobrarlo dos veces.
        Debt deuda = existente(Debt.Status.paid);
        pagadaHastaSeptiembre(deuda);

        ResultadoDeuda resultado = recibir(carteraAl("2026-10-18",
                cargo("Arriendo septiembre", "2026-09", "2026-09-05"),
                cargo("Arriendo octubre", "2026-10", "2026-10-05")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("deuda_saldada", resultado.errores().getFirst().codigo());
        assertEquals(Debt.Status.paid, deuda.getStatus());
    }

    @Test
    void una_deuda_pagada_que_vuelve_con_un_solo_mes_todavia_no_entra() throws Exception {
        Debt deuda = existente(Debt.Status.paid);
        pagadaHastaSeptiembre(deuda);

        ResultadoDeuda resultado = recibir(carteraAl("2026-10-18", cargo("Arriendo octubre", "2026-10", "2026-10-05")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("bajo_umbral_mora", resultado.errores().getFirst().codigo());
    }

    @Test
    void una_deuda_pagada_no_se_retira() throws Exception {
        existente(Debt.Status.paid);

        ResultadoDeuda resultado = recibir(retiro("pago_directo"));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("deuda_saldada", resultado.errores().getFirst().codigo());
    }

    @Test
    void la_agencia_devuelve_un_caso_que_paso_su_mora_maxima() throws Exception {
        Debt deuda = existente(Debt.Status.open);

        ResultadoDeuda resultado = recibir(retiro("fuera_de_mandato"));

        assertEquals("retirada", resultado.resultado());
        assertEquals(Debt.Status.withdrawn, deuda.getStatus());
        assertNull(resultado.errores());
    }

    private JsonNode retiro(String motivo) throws Exception {
        return json.readTree("""
                {"version": "1.0",
                 "lote": {"id_externo": "APX-2026-10-19-005", "fecha_corte": "2026-10-18",
                          "acreedor": {"rut": "76418902-7"}},
                 "deudas": [{"id_externo": "CTR-2026-031", "accion": "retirar", "motivo_retiro": "%s"}]}
                """.formatted(motivo));
    }

    @Test
    void un_umbral_menor_que_uno_no_deja_arrancar_el_servicio() {
        assertThrows(IllegalStateException.class, () -> new CarteraIntakeService(organizations, mandates,
                campaigns, batches, debtors, debts, charges, installments, events, json, eventos, avisos, 0));
    }

    // ------------------------------------------------------------------
    //  Todos los clientes con contrato: el que esta al dia viene sin cargos
    // ------------------------------------------------------------------

    @Test
    void un_cliente_al_dia_se_acepta_sin_guardar_nada() throws Exception {
        CarteraResponse respuesta = servicio.recibir(patrimonio, cartera(), Batch.Source.api);

        ResultadoDeuda resultado = respuesta.resultados().getFirst();
        assertEquals("al_dia", resultado.resultado());
        assertEquals(1, respuesta.aceptadas());
        //  De quien no debe nada no se guarda ni un dato.
        verify(debtors, never()).save(any());
        verify(debts, never()).save(any());
        verify(avisos, never()).publishEvent(any());
    }

    @Test
    void al_dia_con_una_deuda_en_gestion_la_cierra_como_pago_directo() throws Exception {
        Debt deuda = existente(Debt.Status.open);
        Installment pendiente = cuota(deuda, 1, LocalDate.of(2026, 9, 5), 820000, null);
        when(installments.findByDebtAndStatus(deuda, Installment.Status.pending)).thenReturn(List.of(pendiente));

        ResultadoDeuda resultado = recibir(cartera());

        assertEquals("retirada", resultado.resultado());
        assertEquals(Debt.Status.withdrawn, deuda.getStatus());
        verify(events).save(argThat(evento -> "pago_directo".equals(evento.getReference())));
        assertEquals(Installment.Status.void_, pendiente.getStatus());
    }

    @Test
    void al_dia_con_una_deuda_ya_pagada_no_cambia_nada() throws Exception {
        Debt deuda = existente(Debt.Status.paid);

        ResultadoDeuda resultado = recibir(cartera());

        assertEquals("al_dia", resultado.resultado());
        assertEquals(Debt.Status.paid, deuda.getStatus());
        verify(debts, never()).save(any());
    }

    // ------------------------------------------------------------------
    //  La invitacion: al deudor le llega su codigo cuando la deuda entra
    // ------------------------------------------------------------------

    @Test
    void una_deuda_que_entra_invita_al_deudor() throws Exception {
        recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        verify(avisos).publishEvent(any(InvitacionService.DeudaEnCobranza.class));
    }

    @Test
    void una_deuda_que_ya_estaba_en_gestion_no_vuelve_a_invitar() throws Exception {
        existente(Debt.Status.open);

        recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        verify(avisos, never()).publishEvent(any());
    }

    @Test
    void una_deuda_pagada_que_vuelve_a_cobranza_invita_de_nuevo() throws Exception {
        existente(Debt.Status.paid);

        ResultadoDeuda resultado = recibir(carteraAl("2026-11-18",
                cargo("Arriendo octubre", "2026-10", "2026-10-05"),
                cargo("Arriendo noviembre", "2026-11", "2026-11-05")));

        assertEquals("actualizada", resultado.resultado());
        verify(avisos).publishEvent(any(InvitacionService.DeudaEnCobranza.class));
    }
}
