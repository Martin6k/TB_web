package com.tbridge.payments.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.dto.request.WebhookRequest;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.model.DebtNotification;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.model.UfValue;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las reglas del cobro: el monto sale de ms-debt, solo se paga lo propio, y
 * confirmar dos veces no cobra dos veces.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final String FELIPE = "16482337-7";
    private static final JwtPrincipal DEUDOR = new JwtPrincipal(FELIPE, null, "DEBTOR", null, FELIPE);

    @Mock private PaymentRepository payments;
    @Mock private PaymentEventRepository eventos;
    @Mock private DebtNotificationRepository avisos;
    @Mock private DebtClient deudas;
    @Mock private UfService uf;
    @Mock private com.tbridge.payments.client.WebpayClient webpay;

    private final WebhookVerifier firmas = new WebhookVerifier("secreto-de-prueba");
    private PaymentService servicio;

    @BeforeEach
    void preparar() {
        servicio = new PaymentService(payments, eventos, avisos, firmas, deudas, uf, webpay, "http://localhost:8080/", "http://localhost:8082/");
        when(payments.save(any())).thenAnswer(llamada -> {
            Payment pago = llamada.getArgument(0);
            if (pago.getId() == null) {
                pago.setId(41L);
            }
            return pago;
        });
        when(avisos.findByPaymentId(any())).thenReturn(Optional.empty());
        when(webpay.createTransaction(any(), any(), anyLong(), any()))
                .thenReturn(new com.tbridge.payments.dto.gateway.WebpayCreateResponse("tok_test", "https://webpay.test/init"));
    }

    private static DebtClient.DebtSnapshot deudaDe(String rut, String moneda, String monto) {
        return new DebtClient.DebtSnapshot(3L, "76418902-7", rut, moneda, new BigDecimal(monto), 12L);
    }

    @Test
    void el_monto_lo_pone_ms_debt_y_no_la_peticion() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        assertEquals(new BigDecimal("410000"), pago.amount());
        assertEquals(410000L, pago.amountClp());
        assertEquals(Payment.Status.created, pago.status());
        assertTrue(pago.checkoutUrl().startsWith("https://webpay.test/init?token_ws=tok_test"));

        // Placeholder gateway (Mercado Pago o Khipu) genera el enlace de pasarela simulada
        PaymentResponse pagoMp = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "mercadopago"));
        assertTrue(pagoMp.checkoutUrl().startsWith("http://localhost:8080/pasarela/41?sig="));
    }

    @Test
    void las_cuotas_elegidas_viajan_a_ms_debt_que_es_quien_pone_el_monto() {
        when(deudas.obtener(3L, List.of(12L, 13L))).thenReturn(deudaDe(FELIPE, "CLP", "280000"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, List.of(12L, 13L), "webpay"));

        assertEquals(new BigDecimal("280000"), pago.amount());
    }

    @Test
    void en_uf_los_pesos_se_fijan_al_abrir_con_la_uf_del_dia() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "UF", "38.50"));
        when(uf.delDia(any())).thenReturn(new UfValue(null, new BigDecimal("39876.54"), "manual"));
        when(uf.aPesos(new BigDecimal("38.50"), new BigDecimal("39876.54"))).thenReturn(1535247L);

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));

        assertEquals(new BigDecimal("39876.54"), pago.ufValue());
        assertEquals(1535247L, pago.amountClp());
    }

    @Test
    void nadie_paga_la_deuda_de_otro() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe("18905214-6", "CLP", "410000"));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(payments, never()).save(any());
    }

    @Test
    void una_empresa_no_paga_deudas() {
        JwtPrincipal empresa = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(empresa, new CheckoutRequest(3L, null, "webpay")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(deudas, never()).obtener(any(), any());
    }

    @Test
    void una_pasarela_que_no_existe_es_400() {
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "paypal")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    private Payment pagoAbierto() {
        Payment pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setDebtorRut(FELIPE);
        pago.setCreditorRut("76418902-7");
        pago.setAmount(new BigDecimal("410000.00"));
        pago.setAmountClp(410000L);
        pago.setCurrency(Payment.Currency.CLP);
        pago.setGateway(Payment.Gateway.webpay);
        pago.setCreatedAt(Instant.now());
        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        return pago;
    }

    private String firmaDe(Payment pago) {
        return firmas.sign("41", pago.getAmount().toPlainString(), "3");
    }

    @Test
    void confirmar_deja_el_aviso_encolado_para_ms_debt() {
        Payment pago = pagoAbierto();

        PaymentResponse confirmado = servicio.confirmPublic(41L, firmaDe(pago));

        assertEquals(Payment.Status.paid, confirmado.status());
        verify(avisos).save(any(DebtNotification.class));
    }

    @Test
    void confirmar_dos_veces_no_cobra_dos_veces() {
        Payment pago = pagoAbierto();
        String firma = firmaDe(pago);

        servicio.confirmPublic(41L, firma);
        servicio.confirmPublic(41L, firma);

        //  Un solo evento "paid" en el libro y un solo aviso.
        ArgumentCaptor<PaymentEvent> libro = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos, times(1)).save(libro.capture());
        assertEquals(PaymentEvent.Type.paid, libro.getValue().getType());
        verify(avisos, times(1)).save(any(DebtNotification.class));
    }

    @Test
    void un_aviso_con_firma_falsa_no_se_aplica_pero_queda_anotado() {
        Payment pago = pagoAbierto();

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.webhook(new WebhookRequest(41L, null, "wp-1", "firma-falsa"), null));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        assertEquals(Payment.Status.created, pago.getStatus());
        ArgumentCaptor<PaymentEvent> libro = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos).save(libro.capture());
        assertEquals(PaymentEvent.Type.failed, libro.getValue().getType());
        assertFalse(libro.getValue().getSignatureOk());
    }

    @Test
    void cada_quien_ve_solo_sus_pagos() {
        pagoAbierto();
        JwtPrincipal otro = new JwtPrincipal("18905214-6", null, "DEBTOR", null, "18905214-6");
        JwtPrincipal otraEmpresa = new JwtPrincipal("9", "x@y.cl", "CREDITOR", "X", "77305118-6");

        assertEquals(41L, servicio.get(DEUDOR, 41L).id());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> servicio.get(otro, 41L)).getStatus());
        //  APOFYX opera la cartera, pero el acreedor del pago es Patrimonio.
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> servicio.get(otraEmpresa, 41L)).getStatus());
    }

    @Test
    void confirm_webpay_exitoso_marca_como_pagado_y_encola_aviso() {
        Payment pago = pagoAbierto();
        pago.setGateway(Payment.Gateway.webpay);
        pago.setGatewayTxnId("tok_123");
        when(payments.findByGatewayAndGatewayTxnId(Payment.Gateway.webpay, "tok_123")).thenReturn(Optional.of(pago));
        when(webpay.commitTransaction("tok_123")).thenReturn(new com.tbridge.payments.dto.gateway.WebpayCommitResponse(
                "TSY", 410000L, "AUTHORIZED", "ORD41T123", "SESS41", null, "1003", "2026-10-03", "1213", "VN", 0, null, 0, null
        ));

        PaymentService.WebpayCommitResult resultado = servicio.confirmWebpay("tok_123", null);

        assertTrue(resultado.success());
        assertEquals(Payment.Status.paid, resultado.payment().status());
        verify(avisos).save(any(DebtNotification.class));
    }

    @Test
    void confirm_webpay_rechazado_marca_como_fallido() {
        Payment pago = pagoAbierto();
        pago.setGateway(Payment.Gateway.webpay);
        pago.setGatewayTxnId("tok_fail");
        when(payments.findByGatewayAndGatewayTxnId(Payment.Gateway.webpay, "tok_fail")).thenReturn(Optional.of(pago));
        when(webpay.commitTransaction("tok_fail")).thenReturn(new com.tbridge.payments.dto.gateway.WebpayCommitResponse(
                "TSN", 410000L, "FAILED", "ORD41T123", "SESS41", null, "1003", "2026-10-03", null, "VN", -1, null, 0, null
        ));

        PaymentService.WebpayCommitResult resultado = servicio.confirmWebpay("tok_fail", null);

        assertFalse(resultado.success());
        assertEquals(Payment.Status.failed, resultado.payment().status());
    }
}
