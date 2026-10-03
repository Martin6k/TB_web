package com.tbridge.payments.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.client.KhipuClient;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 *
 * <p><b>Khipu cobra de verdad</b> cuando hay {@code KHIPU_LLAVE}: el deudor
 * paga con una transferencia en la pagina de Khipu, y el pago se da por hecho
 * solo cuando Khipu dice que esta conciliado. Webpay y Mercado Pago son
 * simuladas: una pagina propia confirma con la firma del enlace.
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
    private final KhipuClient khipu;
    private final FirmaDeKhipu firmaDeKhipu;
    private final String publicUrl;
    private final String avisosDeKhipu;
    private final Duration venceEn;

    /** Donde Khipu avisa que un pago se concilio. Solo sirve con una direccion publica. */
    public static final String AVISOS_KHIPU = "/api/payments/public/khipu/aviso";

    public PaymentService(
            PaymentRepository payments,
            PaymentEventRepository eventos,
            DebtNotificationRepository avisos,
            WebhookVerifier verifier,
            DebtClient deudas,
            UfService uf,
            KhipuClient khipu,
            FirmaDeKhipu firmaDeKhipu,
            @Value("${app.public-url}") String publicUrl,
            @Value("${app.khipu.url-avisos:}") String avisosDeKhipu,
            @Value("${app.khipu.vence-en:30m}") Duration venceEn
    ) {
        this.payments = payments;
        this.eventos = eventos;
        this.avisos = avisos;
        this.verifier = verifier;
        this.deudas = deudas;
        this.uf = uf;
        this.khipu = khipu;
        this.firmaDeKhipu = firmaDeKhipu;
        this.publicUrl = publicUrl.replaceAll("/$", "");
        this.avisosDeKhipu = avisosDeKhipu == null || avisosDeKhipu.isBlank() ? null
                : avisosDeKhipu.trim().replaceAll("/$", "");
        this.venceEn = venceEn;
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

        if (cobraDeVerdad(pago)) {
            //  El cobro se abre en Khipu ahora, y su id queda como el del pago
            //  en la pasarela. Khipu devuelve al deudor a la pagina del
            //  resultado, que le pregunta a Khipu en que quedo.
            KhipuClient.Cobro cobro = khipu.crear(transaccion(pago), "Pago de deuda en cobranza N° " + pago.getDebtId(),
                    pago.getAmountClp(), enlaceDePago(pago), enlaceDePago(pago) + "&cancelado=1",
                    avisosDeKhipu == null ? null : avisosDeKhipu + AVISOS_KHIPU,
                    ZonedDateTime.now(CHILE).plus(venceEn).toOffsetDateTime());
            pago.setGatewayTxnId(cobro.paymentId());
            payments.save(pago);
            return respuesta(pago).conEnlaceDePago(cobro.paymentUrl());
        }
        return respuesta(pago).conEnlaceDePago(enlaceDePago(pago));
    }

    private String enlaceDePago(Payment pago) {
        return publicUrl + "/pasarela/" + pago.getId() + "?sig=" + firma(pago);
    }

    /** Si este pago lo cobra una pasarela real y no la simulacion. */
    private boolean cobraDeVerdad(Payment pago) {
        return pago.getGateway() == Payment.Gateway.khipu && khipu.real();
    }

    private PaymentResponse respuesta(Payment pago) {
        return PaymentResponse.from(pago, !cobraDeVerdad(pago));
    }

    /** El id de la transaccion en Khipu: corto, y con el id del pago adentro. */
    private static String transaccion(Payment pago) {
        return "TB-" + pago.getId();
    }

    /**
     * La firma del enlace de pago. No se guarda: se recalcula. Guardar una
     * firma que se puede derivar es una copia mas que puede desincronizarse.
     */
    private String firma(Payment pago) {
        return verifier.sign(String.valueOf(pago.getId()), pago.getAmount().toPlainString(),
                String.valueOf(pago.getDebtId()));
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
        return respuesta(visible(user, id));
    }

    public PaymentResponse publicGet(Long id, String sig) {
        return respuesta(conFirmaValida(id, sig));
    }

    /** Lo que ve cada quien: el acreedor, SOLO lo suyo. */
    public List<PaymentResponse> list(JwtPrincipal user) {
        if (user == null || user.rut() == null) {
            return List.of();
        }
        List<Payment> filas = user.isCreditor()
                ? payments.findByCreditorRutOrderByCreatedAtDesc(user.rut())
                : payments.findByDebtorRutOrderByCreatedAtDesc(user.rut());
        return filas.stream().map(this::respuesta).toList();
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
        Payment pago = conFirmaValida(id, sig);
        //  La confirmacion "a mano" es la de la pasarela simulada. Un pago de
        //  Khipu lo confirma solo Khipu: si no, cualquiera con el enlace podria
        //  darlo por pagado sin pagar.
        if (cobraDeVerdad(pago)) {
            throw new ApiException(HttpStatus.CONFLICT, "Este pago lo confirma Khipu, al terminar de pagar alla");
        }
        return confirmar(pago, PaymentEvent.Source.portal, null, null, null);
    }

    // ------------------------------------------------------------------
    //  Khipu
    // ------------------------------------------------------------------

    /**
     * Le pregunta a Khipu en que va el pago. Lo llama la pagina del resultado,
     * a la que Khipu devuelve al deudor: Khipu no dice nada al devolverlo, asi
     * que hay que preguntar. Un pago simulado o ya cerrado se devuelve tal cual.
     */
    @Transactional
    public PaymentResponse verificar(Long id, String sig) {
        Payment pago = conFirmaValida(id, sig);
        if (cobraDeVerdad(pago)) {
            conciliar(pago);
        }
        return respuesta(pago);
    }

    /**
     * El deudor se arrepintio en Khipu y volvio por la {@code cancel_url}.
     * Antes de darlo por fallido se le pregunta a Khipu: si alcanzo a pagar,
     * el pago vale.
     */
    @Transactional
    public PaymentResponse cancelar(Long id, String sig) {
        Payment pago = conFirmaValida(id, sig);
        if (cobraDeVerdad(pago)) {
            conciliar(pago);
            if (pago.getStatus() == Payment.Status.created) {
                fallido(pago, null);
            }
        }
        return respuesta(pago);
    }

    /**
     * El aviso de Khipu: un pago se concilio. Solo llega si DataBridge tiene
     * una direccion publica ({@code KHIPU_URL_AVISOS}). No se aplica lo que
     * dice: se verifica su firma y se le pregunta a Khipu, asi que un aviso
     * falso o repetido no cobra nada.
     */
    @Transactional
    public void avisoDeKhipu(String cuerpo, String firma, String paymentId) {
        if (!firmaDeKhipu.valida(firma, cuerpo)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Firma de Khipu invalida");
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El aviso no dice que pago es");
        }
        Payment pago = payments.findByGatewayAndGatewayTxnId(Payment.Gateway.khipu, paymentId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Khipu aviso un pago que no existe"));
        conciliar(pago);
    }

    /**
     * Los cobros de Khipu abiertos: se le pregunta a Khipu por cada uno. Asi
     * un pago se registra aunque el deudor cierre la ventana antes de volver,
     * y sin depender del aviso, que en local no puede llegar. El que paso el
     * plazo sin pagarse queda vencido. Devuelve cuantos se cerraron.
     */
    @Transactional
    public int conciliarPendientes() {
        if (!khipu.real()) {
            return 0;
        }
        int cerrados = 0;
        Instant limite = Instant.now().minus(venceEn);
        for (Payment pago : payments.findByGatewayAndStatus(Payment.Gateway.khipu, Payment.Status.created)) {
            try {
                conciliar(pago);
            } catch (ApiException khipuNoResponde) {
                continue;
            }
            if (pago.getStatus() == Payment.Status.created && pago.getCreatedAt().isBefore(limite)) {
                pago.setStatus(Payment.Status.expired);
                payments.save(pago);
                eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.expired, PaymentEvent.Source.webhook));
            }
            if (pago.getStatus() != Payment.Status.created) {
                cerrados++;
            }
        }
        return cerrados;
    }

    /**
     * Lo que dice Khipu, aplicado al pago. Pagado solo si Khipu lo concilio,
     * el monto es el que se cobro y la transaccion es la nuestra; terminado
     * sin plata, fallido; todavia en curso, no cambia nada.
     */
    private void conciliar(Payment pago) {
        if (pago.getStatus() != Payment.Status.created || pago.getGatewayTxnId() == null) {
            return;
        }
        KhipuClient.Estado estado = khipu.estado(pago.getGatewayTxnId());
        String crudo = estado.crudo() == null ? null : estado.crudo().toString();
        boolean calza = estado.amount() != null && estado.amount().compareTo(BigDecimal.valueOf(pago.getAmountClp())) == 0
                && transaccion(pago).equals(estado.transactionId());
        if (estado.pagado() && calza) {
            confirmar(pago, PaymentEvent.Source.webhook, pago.getGatewayTxnId(), null, crudo);
        } else if (estado.sinCobro() || estado.pagado()) {
            //  Pagado pero por otro monto u otra transaccion no se acepta: no
            //  es el cobro que se abrio.
            fallido(pago, crudo);
        }
    }

    private void fallido(Payment pago, String crudo) {
        pago.setStatus(Payment.Status.failed);
        payments.save(pago);
        eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.failed, PaymentEvent.Source.webhook)
                .conRespuesta(crudo));
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
        if (cobraDeVerdad(pago)) {
            throw new ApiException(HttpStatus.CONFLICT, "Este pago lo confirma Khipu");
        }
        return confirmar(pago, PaymentEvent.Source.webhook, aviso.txnId(), true, null);
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

    private PaymentResponse confirmar(Payment pago, PaymentEvent.Source origen, String txnId, Boolean firmaOk,
                                      String respuestaDeLaPasarela) {
        //  Idempotencia: el pago ya cobrado se devuelve tal cual.
        if (pago.getStatus() == Payment.Status.paid) {
            return respuesta(pago);
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
            eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.paid, origen).conFirma(firmaOk)
                    .conRespuesta(respuestaDeLaPasarela));
            //  El aviso queda encolado aqui, en la misma transaccion. Si
            //  ms-debt esta caido, el pago igual quedo guardado.
            if (avisos.findByPaymentId(pago.getId()).isEmpty()) {
                avisos.save(DebtNotification.para(pago.getId()));
            }
        } catch (DataIntegrityViolationException choque) {
            //  El unico de la pasarela salto: ese aviso ya se habia aplicado.
            throw new ApiException(HttpStatus.CONFLICT, "Ese pago de la pasarela ya estaba registrado");
        }
        return respuesta(pago);
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
