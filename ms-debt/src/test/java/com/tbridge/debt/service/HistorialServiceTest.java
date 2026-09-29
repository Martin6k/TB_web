package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.response.PagoResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El historial sale de los pagos abonados, con lo que cubrio cada uno. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistorialServiceTest {

    @Mock private DebtService debts;
    @Mock private DebtEventRepository events;

    private HistorialService historial;
    private Debt deuda;
    private final JwtPrincipal felipe = new JwtPrincipal("16482337-7", null, "DEBTOR", null, "16482337-7");

    @BeforeEach
    void preparar() {
        historial = new HistorialService(debts, events, new ObjectMapper());
        Organization patrimonio = new Organization();
        patrimonio.setId(1L);
        patrimonio.setRut("76418902-7");
        patrimonio.setTradeName("Patrimonio Inmuebles");
        Debtor deudor = new Debtor();
        deudor.setRut("16482337-7");
        deudor.setFullName("Felipe Rojas Muñoz");
        deuda = new Debt();
        deuda.setId(3L);
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(deudor);
        deuda.setExternalId("CTR-2025-014");
        deuda.setConcept("Arriendo mensual");
        deuda.setCurrency(Debt.Currency.CLP);
        when(debts.deudasVisibles(felipe)).thenReturn(List.of(deuda));
    }

    private DebtEvent pago(String referencia, String detalle) {
        return DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                .conMonto(new BigDecimal("346666"), Debt.Currency.CLP)
                .conReferencia(referencia)
                .conDetalle(detalle);
    }

    @Test
    void cada_pago_dice_que_cuotas_cubrio_y_por_donde() {
        when(events.findTop300ByDebtInAndTypeOrderByOccurredAtDesc(List.of(deuda), DebtEvent.Type.payment_applied))
                .thenReturn(List.of(pago("webpay:wp-9f31c2", """
                        {"pago_id":41,"monto_clp":346666,"valor_uf":null,"pasarela":"webpay","cuotas":[4,5],"de":6}""")));

        PagoResponse pago = historial.pagos(felipe).getFirst();

        assertEquals(List.of(4, 5), pago.cuotas());
        assertEquals(6, pago.deCuotas());
        assertEquals("webpay", pago.pasarela());
        assertEquals("wp-9f31c2", pago.referencia());
        assertEquals(346666L, pago.montoClp());
        assertEquals("CTR-2025-014", pago.externalId());
    }

    @Test
    void un_pago_anterior_al_detalle_saca_la_pasarela_de_la_referencia() {
        when(events.findTop300ByDebtInAndTypeOrderByOccurredAtDesc(List.of(deuda), DebtEvent.Type.payment_applied))
                .thenReturn(List.of(pago("khipu:kh-123", null)));

        PagoResponse pago = historial.pagos(felipe).getFirst();

        assertEquals("khipu", pago.pasarela());
        assertEquals("kh-123", pago.referencia());
        assertTrue(pago.cuotas().isEmpty());
        assertNull(pago.deCuotas());
    }

    @Test
    void sin_deudas_no_hay_historial_ni_consulta() {
        when(debts.deudasVisibles(felipe)).thenReturn(List.of());

        assertTrue(historial.pagos(felipe).isEmpty());
        verify(events, never()).findTop300ByDebtInAndTypeOrderByOccurredAtDesc(any(), any());
    }

    @Test
    void el_pago_de_una_deuda_ajena_no_se_entrega() {
        DebtEvent ajeno = pago("webpay:wp-1", null);
        when(events.findById(57L)).thenReturn(Optional.of(ajeno));
        when(debts.requireVisible(any(), any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "No puedes ver esta deuda"));

        ApiException error = assertThrows(ApiException.class, () -> historial.pago(felipe, 57L));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void un_evento_que_no_es_un_pago_no_tiene_comprobante() {
        when(events.findById(58L)).thenReturn(Optional.of(
                DebtEvent.de(deuda, DebtEvent.Type.repacted, DebtEvent.Actor.debtor)));

        ApiException error = assertThrows(ApiException.class, () -> historial.pago(felipe, 58L));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void el_comprobante_dice_que_se_pago_con_palabras() {
        assertEquals("Cuotas 4 y 5 de 6", ComprobanteService.queSePago(pago(List.of(4, 5), 6, null)));
        assertEquals("Cuota 3 de 12", ComprobanteService.queSePago(pago(List.of(3), 12, null)));
        assertEquals("Cuotas 1, 2 y 3 de 3", ComprobanteService.queSePago(pago(List.of(1, 2, 3), 3, null)));
        assertEquals("El total de la deuda", ComprobanteService.queSePago(pago(List.of(1), 1, null)));
    }

    @Test
    void un_mes_informado_despues_del_convenio_se_nombra_aparte() {
        assertEquals("Fuera del convenio", ComprobanteService.queSePago(pago(List.of(), 6, 1)));
        assertEquals("Fuera del convenio y cuota 3 de 6", ComprobanteService.queSePago(pago(List.of(3), 6, 1)));
        assertEquals("Fuera del convenio y cuotas 3 y 4 de 6",
                ComprobanteService.queSePago(pago(List.of(3, 4), 6, 1)));
    }

    private static PagoResponse pago(List<Integer> cuotas, Integer de, Integer fuera) {
        return new PagoResponse(1L, 3L, "X", "A", "D", "1-9", "C", Debt.Currency.CLP, BigDecimal.ONE,
                1L, null, "webpay", "r", cuotas, de, fuera, Instant.now());
    }

    @Test
    void el_comprobante_es_un_pdf() {
        DebtEvent evento = pago("webpay:wp-9f31c2", """
                {"pago_id":41,"monto_clp":346666,"pasarela":"webpay","cuotas":[4,5],"de":6}""");
        evento.setOccurredAt(Instant.parse("2026-09-23T15:30:00Z"));
        when(events.findById(57L)).thenReturn(Optional.of(evento));

        byte[] pdf = new ComprobanteService(historial).generar(felipe, 57L);

        assertEquals("%PDF", new String(pdf, 0, 4));
        assertTrue(pdf.length > 1000);
    }
}
