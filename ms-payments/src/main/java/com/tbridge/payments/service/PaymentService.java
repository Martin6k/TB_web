package com.tbridge.payments.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.dto.request.WebhookRequest;
import com.tbridge.payments.dto.response.HistoriaResponse;
import com.tbridge.payments.dto.response.PaymentEventResponse;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.model.DebtNotification;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.model.UfValue;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * El cobro.
 *
 * <p>Tres reglas gobiernan este archivo:
 *
 * <ol>
 *   <li><b>El monto no lo decide el cliente.</b> Sale de ms-debt, que es quien
 *       manda sobre la deuda.</li>
 *   <li><b>Nada se sobreescribe.</b> Cada transicion queda como una fila nueva
 *       en el libro; el estado del pago es solo la proyeccion del ultimo.</li>
 *   <li><b>El aviso a ms-debt no se manda aqui.</b> Se deja encolado en la
 *       misma transaccion y sale despues, con reintentos.</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final PaymentRepository payments;
    private final PaymentEventRepository eventos;
    private final DebtNotificationRepository avisos;
    private final WebhookVerifier verifier;
    private final DebtClient deudas;
    private final UfService uf;
    private final WebpayClient webpay;
    private final String publicUrl;

    public record WebpayCommitResult(PaymentResponse payment, boolean success, String message) {}

    public PaymentService(
            PaymentRepository payments,
            PaymentEventRepository eventos,
            DebtNotificationRepository avisos,
            WebhookVerifier verifier,
            DebtClient deudas,
            UfService uf,
            WebpayClient webpay,
            @Value("${app.public-url}") String publicUrl
    ) {
        this.payments = payments;
        this.eventos = eventos;
        this.avisos = avisos;
        this.verifier = verifier;
        this.deudas = deudas;
        this.uf = uf;
        this.webpay = webpay;
        this.publicUrl = publicUrl.replaceAll("/$", "");
    }

    // ------------------------------------------------------------------
    //  Iniciar el pago
    // ------------------------------------------------------------------

    @Transactional
    public PaymentResponse checkout(JwtPrincipal user, CheckoutRequest pedido) {
        if (user != null && user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El acreedor no paga deudas");
        }
        //  Solo se paga lo propio, y lo propio se decide por RUT: es lo unico
        //  que trae la sesion de un deudor, que entra con su codigo, sin
        //  cuenta ni correo.
        if (user == null || user.rut() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La sesion no identifica al deudor");
        }
        Payment.Gateway gateway = pasarela(pedido.gateway());

        //  El monto y a quien se le debe salen de ms-debt, no del cuerpo.
        DebtClient.DebtSnapshot deuda = deudas.obtener(pedido.debtId(), pedido.installmentIds());
        if (!user.rut().equalsIgnoreCase(deuda.debtorRut())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Esa deuda no es tuya");
        }

        Payment pago = new Payment();
        pago.setDebtId(deuda.debtId());
        pago.setInstallmentId(deuda.installmentId());
        pago.setDebtorRut(deuda.debtorRut());
        pago.setCreditorRut(deuda.creditorRut());
        pago.setAmount(deuda.amount());
        pago.setCurrency(Payment.Currency.valueOf(deuda.currency()));
        pago.setGateway(gateway);
        pago.setStatus(Payment.Status.created);
        pago.setCreatedAt(Instant.now());
        //  Los pesos se fijan al abrir el cobro, porque es lo que la pasarela
        //  le cobra al deudor. Calcularlos al confirmar dejaba un pago abierto
        //  a las 23:59 y confirmado a las 00:01 registrado con otra UF que la
        //  que se cobro, y la conciliacion no cuadraba.
        fijarPesos(pago);
        payments.save(pago);
        eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.created, PaymentEvent.Source.portal));

        String checkoutUrl;
        if (gateway == Payment.Gateway.webpay) {
            // Integración real Transbank Webpay Plus (Ambiente TEST)
            String buyOrder = "ORD" + pago.getId() + "T" + (System.currentTimeMillis() % 100000);
            String sessionId = "SESS" + pago.getId();
            String returnUrl = publicUrl + "/api/payments/webpay/return";
            com.tbridge.payments.dto.gateway.WebpayCreateResponse webpayResp = webpay.createTransaction(
                    buyOrder,
                    sessionId,
                    pago.getAmountClp(),
                    returnUrl
            );
            pago.setGatewayTxnId(webpayResp.token());
            payments.save(pago);
            checkoutUrl = webpayResp.url() + "?token_ws=" + webpayResp.token();
        } else {
            // Placeholder / Simulación para Mercado Pago y Khipu
            checkoutUrl = enlaceDePago(pago);
        }

        return PaymentResponse.from(pago).conEnlaceDePago(checkoutUrl);
    }

    private String enlaceDePago(Payment pago) {
        return publicUrl + "/pasarela/" + pago.getId() + "?sig=" + firma(pago);
    }

    /**
     * La firma del enlace de pago. No se guarda: se recalcula. Guardar una
     * firma que se puede derivar es una copia mas que puede desincronizarse.
     */
    private String firma(Payment pago) {
        return verifier.sign(String.valueOf(pago.getId()), pago.getAmount().toPlainString(),
                String.valueOf(pago.getDebtId()));
    }

    /**
     * Confirma una transacción que retorna desde Transbank Webpay Plus.
     */
    @Transactional
    public WebpayCommitResult confirmWebpay(String tokenWs, String tbkToken) {
        if (tokenWs == null || tokenWs.isBlank()) {
            if (tbkToken != null && !tbkToken.isBlank()) {
                payments.findByGatewayAndGatewayTxnId(Payment.Gateway.webpay, tbkToken)
                        .ifPresent(p -> {
                            if (p.getStatus() == Payment.Status.created) {
                                p.setStatus(Payment.Status.failed);
                                payments.save(p);
                                eventos.save(PaymentEvent.de(p.getId(), PaymentEvent.Type.failed, PaymentEvent.Source.portal));
                            }
                        });
            }
            return new WebpayCommitResult(null, false, "Transacción cancelada o abortada en Webpay");
        }

        Payment pago = payments.findByGatewayAndGatewayTxnId(Payment.Gateway.webpay, tokenWs).orElse(null);
        com.tbridge.payments.dto.gateway.WebpayCommitResponse commitResp = webpay.commitTransaction(tokenWs);

        if (pago == null && commitResp.buyOrder() != null) {
            try {
                String bo = commitResp.buyOrder();
                if (bo.startsWith("ORD")) {
                    int tIdx = bo.indexOf('T');
                    String idStr = tIdx > 3 ? bo.substring(3, tIdx) : bo.substring(3);
                    pago = payments.findById(Long.parseLong(idStr)).orElse(null);
                }
            } catch (Exception ignored) {
            }
        }

        if (pago == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Pago Webpay no encontrado para el token proporcionado");
        }

        if (commitResp.isAuthorized()) {
            String authCode = commitResp.authorizationCode() != null ? commitResp.authorizationCode() : "1213";
            String uniqueTxnId = "tbk-" + pago.getId() + "-" + authCode;
            PaymentResponse resp = confirmar(pago, PaymentEvent.Source.portal, uniqueTxnId, true);
            return new WebpayCommitResult(resp, true, "Pago aprobado");
        } else {
            pago.setStatus(Payment.Status.failed);
            payments.save(pago);
            eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.failed, PaymentEvent.Source.portal));
            return new WebpayCommitResult(PaymentResponse.from(pago), false, "Pago rechazado por Transbank");
        }
    }

    // ------------------------------------------------------------------
    //  Consultar
    // ------------------------------------------------------------------

    /**
     * Un pago, si a quien pregunta le corresponde verlo: al deudor, los suyos;
     * a la empresa, los de su cartera. Todo por RUT, que es lo que traen las
     * sesiones.
     */
    public PaymentResponse get(JwtPrincipal user, Long id) {
        return PaymentResponse.from(visible(user, id));
    }

    public PaymentResponse publicGet(Long id, String sig) {
        return PaymentResponse.from(conFirmaValida(id, sig));
    }

    /** Lo que ve cada quien: el acreedor, SOLO lo suyo. */
    public List<PaymentResponse> list(JwtPrincipal user) {
        if (user == null || user.rut() == null) {
            return List.of();
        }
        List<Payment> filas = user.isCreditor()
                ? payments.findByCreditorRutOrderByCreatedAtDesc(user.rut())
                : payments.findByDebtorRutOrderByCreatedAtDesc(user.rut());
        return filas.stream().map(PaymentResponse::from).toList();
    }

    /** El libro de un pago, para el panel y para auditar. */
    public HistoriaResponse historia(JwtPrincipal user, Long id) {
        visible(user, id);
        return new HistoriaResponse(eventos.findByPaymentIdOrderByIdAsc(id).stream()
                .map(PaymentEventResponse::from)
                .toList());
    }

    private Payment visible(JwtPrincipal user, Long id) {
        Payment pago = buscar(id);
        String suyo = user == null ? null
                : user.isCreditor() ? pago.getCreditorRut() : pago.getDebtorRut();
        if (!mismo(user == null ? null : user.rut(), suyo)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No puedes ver este pago");
        }
        return pago;
    }

    // ------------------------------------------------------------------
    //  Confirmar
    // ------------------------------------------------------------------

    @Transactional
    public PaymentResponse confirmPublic(Long id, String sig) {
        return confirmar(conFirmaValida(id, sig), PaymentEvent.Source.portal, null, null);
    }

    /**
     * El aviso de la pasarela.
     *
     * <p>Un webhook se reintenta: el mismo aviso puede llegar dos o tres
     * veces. La segunda no vuelve a cobrar, y la base lo garantiza con el
     * unico (gateway, gateway_txn_id) por si el codigo se equivoca.
     */
    @Transactional
    public PaymentResponse webhook(WebhookRequest aviso, String signatureHeader) {
        Long id = aviso.pago();
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El aviso no dice que pago es");
        }
        Payment pago = buscar(id);

        String sig = signatureHeader == null || signatureHeader.isBlank() ? aviso.signature() : signatureHeader;
        boolean firmaOk = verifier.matches(sig, String.valueOf(pago.getId()),
                pago.getAmount().toPlainString(), String.valueOf(pago.getDebtId()));

        if (!firmaOk) {
            //  Un aviso con firma invalida no se aplica, pero se guarda:
            //  alguien mandando avisos falsos es algo que hay que poder ver.
            eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.failed, PaymentEvent.Source.webhook)
                    .conFirma(false));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Firma criptografica invalida");
        }
        return confirmar(pago, PaymentEvent.Source.webhook, aviso.txnId(), true);
    }

    /** El monto en pesos, con el valor de la UF del dia en Chile si la deuda es en UF. */
    private void fijarPesos(Payment pago) {
        if (pago.getCurrency() == Payment.Currency.UF) {
            UfValue valor = uf.delDia(LocalDate.now(CHILE));
            pago.setUfValue(valor.getValue());
            pago.setAmountClp(uf.aPesos(pago.getAmount(), valor.getValue()));
        } else {
            pago.setAmountClp(pago.getAmount().longValue());
        }
    }

    private PaymentResponse confirmar(Payment pago, PaymentEvent.Source origen, String txnId, Boolean firmaOk) {
        //  Idempotencia: el pago ya cobrado se devuelve tal cual.
        if (pago.getStatus() == Payment.Status.paid) {
            return PaymentResponse.from(pago);
        }
        //  Solo los cobros abiertos antes de que se fijaran al abrir.
        if (pago.getAmountClp() == null) {
            fijarPesos(pago);
        }

        pago.setGatewayTxnId(txnId == null || txnId.isBlank() ? "int-" + pago.getId() : txnId.trim());
        pago.setStatus(Payment.Status.paid);
        pago.setPaidAt(Instant.now());

        try {
            payments.save(pago);
            eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.paid, origen).conFirma(firmaOk));
            //  El aviso queda encolado aqui, en la misma transaccion. Si
            //  ms-debt esta caido, el pago igual quedo guardado.
            if (avisos.findByPaymentId(pago.getId()).isEmpty()) {
                avisos.save(DebtNotification.para(pago.getId()));
            }
        } catch (DataIntegrityViolationException choque) {
            //  El unico de la pasarela salto: ese aviso ya se habia aplicado.
            throw new ApiException(HttpStatus.CONFLICT, "Ese pago de la pasarela ya estaba registrado");
        }
        return PaymentResponse.from(pago);
    }

    // ------------------------------------------------------------------
    //  Auxiliares
    // ------------------------------------------------------------------

    private Payment buscar(Long id) {
        return payments.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
    }

    private Payment conFirmaValida(Long id, String sig) {
        Payment pago = buscar(id);
        if (!verifier.matches(sig, String.valueOf(pago.getId()),
                pago.getAmount().toPlainString(), String.valueOf(pago.getDebtId()))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Firma de checkout invalida");
        }
        return pago;
    }

    private static boolean mismo(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static Payment.Gateway pasarela(String valor) {
        try {
            return Payment.Gateway.valueOf((valor == null ? "" : valor.trim()).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pasarela no soportada (Mercado Pago, Khipu o Webpay)");
        }
    }
}
