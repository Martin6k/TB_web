package com.tbridge.payments.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.client.KhipuClient;
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
    @Mock private KhipuClient khipu;

    private final WebhookVerifier firmas = new WebhookVerifier("secreto-de-prueba");
    private final FirmaDeKhipu firmaDeKhipu = new FirmaDeKhipu("secreto-de-khipu");
    private PaymentService servicio;

    @BeforeEach
    void preparar() {
        servicio = new PaymentService(payments, eventos, avisos, firmas, deudas, uf, khipu, firmaDeKhipu,
                "http://localhost:8080/", "", java.time.Duration.ofMinutes(30));
        when(payments.save(any())).thenAnswer(llamada -> {
            Payment pago = llamada.getArgument(0);
            if (pago.getId() == null) {
                pago.setId(41L);
            }
            return pago;
        });
        when(avisos.findByPaymentId(any())).thenReturn(Optional.empty());
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
        assertTrue(pago.checkoutUrl().startsWith("http://localhost:8080/pasarela/41?sig="));
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

    // ------------------------------------------------------------------
    //  Khipu de verdad (con KHIPU_LLAVE)
    // ------------------------------------------------------------------

    private static final String KHIPU_ID = "gqzdy6chjne9";

    private PaymentResponse cobroConKhipu() {
        when(khipu.real()).thenReturn(true);
        when(khipu.crear(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new KhipuClient.Cobro(KHIPU_ID, "https://khipu.com/payment/info/" + KHIPU_ID));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));
        return servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));
    }

    /** El pago recien abierto en Khipu, como lo guarda el checkout. */
    private Payment abierto() {
        Payment pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setDebtorRut(FELIPE);
        pago.setCreditorRut("76418902-7");
        pago.setAmount(new BigDecimal("410000"));
        pago.setAmountClp(410000L);
        pago.setCurrency(Payment.Currency.CLP);
        pago.setGateway(Payment.Gateway.khipu);
        pago.setStatus(Payment.Status.created);
        pago.setGatewayTxnId(KHIPU_ID);
        pago.setCreatedAt(Instant.now());
        when(khipu.real()).thenReturn(true);
        when(payments.findByGatewayAndGatewayTxnId(Payment.Gateway.khipu, KHIPU_ID)).thenReturn(Optional.of(pago));
        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        return pago;
    }

    private static KhipuClient.Estado estado(String status, String detalle, String monto, String transaccion)
            throws Exception {
        return new KhipuClient.Estado(status, detalle, new BigDecimal(monto), "CLP", transaccion,
                new ObjectMapper().readTree("{\"status\":\"" + status + "\",\"status_detail\":\"" + detalle + "\"}"));
    }

    @Test
    void con_khipu_el_cobro_se_abre_en_khipu_con_el_monto_en_pesos() {
        PaymentResponse pago = cobroConKhipu();

        ArgumentCaptor<String> retorno = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cancelado = ArgumentCaptor.forClass(String.class);
        verify(khipu).crear(org.mockito.ArgumentMatchers.eq("TB-41"), any(), org.mockito.ArgumentMatchers.eq(410000L),
                retorno.capture(), cancelado.capture(), org.mockito.ArgumentMatchers.isNull(), any());
        assertTrue(retorno.getValue().startsWith("http://localhost:8080/pasarela/41?sig="));
        assertTrue(cancelado.getValue().endsWith("&cancelado=1"));
        assertEquals("https://khipu.com/payment/info/" + KHIPU_ID, pago.checkoutUrl(), "se paga en la pagina de Khipu");
        assertFalse(pago.simulada());
    }

    @Test
    void al_volver_con_el_pago_conciliado_se_confirma_y_se_avisa_a_ms_debt() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000.0000", "TB-41"));

        PaymentResponse respuesta = servicio.verificar(41L, firmaDe(pago));

        assertEquals(Payment.Status.paid, respuesta.status());
        assertEquals(KHIPU_ID, pago.getGatewayTxnId());
        verify(avisos).save(any(DebtNotification.class));
        ArgumentCaptor<PaymentEvent> evento = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos).save(evento.capture());
        assertEquals(PaymentEvent.Type.paid, evento.getValue().getType());
        assertTrue(evento.getValue().getGatewayPayload().contains("done"), "se guarda lo que dijo Khipu");
    }

    @Test
    void mientras_khipu_verifica_la_transferencia_el_pago_sigue_abierto() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("verifying", "pending", "410000", "TB-41"));

        servicio.verificar(41L, firmaDe(pago));

        assertEquals(Payment.Status.created, pago.getStatus());
        verify(avisos, never()).save(any());
    }

    @Test
    void otro_monto_u_otra_transaccion_no_se_dan_por_pagados() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "1", "TB-41"));
        servicio.verificar(41L, firmaDe(pago));
        assertEquals(Payment.Status.failed, pago.getStatus());

        Payment otro = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-99"));
        servicio.verificar(41L, firmaDe(otro));
        assertEquals(Payment.Status.failed, otro.getStatus());

        verify(avisos, never()).save(any());
    }

    @Test
    void rechazado_o_revertido_queda_fallido() throws Exception {
        Payment rechazado = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "rejected-by-payer", "410000", "TB-41"));
        servicio.verificar(41L, firmaDe(rechazado));
        assertEquals(Payment.Status.failed, rechazado.getStatus());

        Payment revertido = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "reversed", "410000", "TB-41"));
        servicio.verificar(41L, firmaDe(revertido));
        assertEquals(Payment.Status.failed, revertido.getStatus());
    }

    @Test
    void si_el_deudor_se_arrepiente_queda_fallido_salvo_que_haya_alcanzado_a_pagar() throws Exception {
        Payment arrepentido = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("pending", "pending", "410000", "TB-41"));
        assertEquals(Payment.Status.failed, servicio.cancelar(41L, firmaDe(arrepentido)).status());

        Payment alcanzo = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));
        assertEquals(Payment.Status.paid, servicio.cancelar(41L, firmaDe(alcanzo)).status());
    }

    @Test
    void verificar_un_pago_ya_cerrado_no_le_pregunta_otra_vez_a_khipu() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));

        servicio.verificar(41L, firmaDe(pago));
        servicio.verificar(41L, firmaDe(pago));

        verify(khipu, times(1)).estado(KHIPU_ID);
        assertEquals(Payment.Status.paid, pago.getStatus());
    }

    @Test
    void un_pago_de_khipu_no_se_confirma_desde_la_pagina_simulada() {
        Payment pago = abierto();

        ApiException error = assertThrows(ApiException.class, () -> servicio.confirmPublic(41L, firmaDe(pago)));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(Payment.Status.created, pago.getStatus());
    }

    @Test
    void sin_llave_khipu_sigue_simulada() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));

        assertTrue(pago.simulada());
        assertTrue(pago.checkoutUrl().startsWith("http://localhost:8080/pasarela/41?sig="));
        verify(khipu, never()).crear(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void la_consulta_periodica_cierra_lo_pagado_y_vence_lo_abandonado() throws Exception {
        Payment pagado = abierto();
        Payment abandonado = new Payment();
        abandonado.setId(42L);
        abandonado.setDebtId(4L);
        abandonado.setAmount(new BigDecimal("1000"));
        abandonado.setAmountClp(1000L);
        abandonado.setGateway(Payment.Gateway.khipu);
        abandonado.setStatus(Payment.Status.created);
        abandonado.setGatewayTxnId("abandonado1");
        abandonado.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        Payment sinRespuesta = new Payment();
        sinRespuesta.setId(43L);
        sinRespuesta.setGateway(Payment.Gateway.khipu);
        sinRespuesta.setStatus(Payment.Status.created);
        sinRespuesta.setGatewayTxnId("caido1");
        sinRespuesta.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(payments.findByGatewayAndStatus(Payment.Gateway.khipu, Payment.Status.created))
                .thenReturn(List.of(pagado, abandonado, sinRespuesta));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));
        when(khipu.estado("abandonado1")).thenReturn(estado("pending", "pending", "1000", "TB-42"));
        when(khipu.estado("caido1")).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Khipu no responde"));

        assertEquals(2, servicio.conciliarPendientes());

        assertEquals(Payment.Status.paid, pagado.getStatus());
        assertEquals(Payment.Status.expired, abandonado.getStatus());
        assertEquals(Payment.Status.created, sinRespuesta.getStatus(), "si Khipu no responde, se reintenta despues");
    }

    @Test
    void el_aviso_de_khipu_se_verifica_y_dispara_la_consulta() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));
        String cuerpo = "{\"payment_id\":\"" + KHIPU_ID + "\",\"amount\":\"1\"}";
        String firma = "t=1711965600393,s=" + java.util.Base64.getEncoder()
                .encodeToString(firmaDeKhipu.firmar("1711965600393." + cuerpo));

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> servicio.avisoDeKhipu(cuerpo, "t=1,s=otra", KHIPU_ID)).getStatus());
        assertEquals(Payment.Status.created, pago.getStatus());

        servicio.avisoDeKhipu(cuerpo, firma, KHIPU_ID);

        assertEquals(Payment.Status.paid, pago.getStatus(), "manda lo que dice Khipu, no el monto del aviso");
    }
}
