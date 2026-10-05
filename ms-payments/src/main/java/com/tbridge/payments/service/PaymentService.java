package com.tbridge.payments.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.payments.client.DebtClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.client.KhipuClient;
import com.tbridge.payments.client.MercadoPagoClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.dto.gateway.WebpayCommitResponse;
import com.tbridge.payments.dto.gateway.WebpayCreateResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Tres pasarelas cobran de verdad, y en las tres el pago se da por hecho solo
 * cuando la pasarela lo confirma, de servidor a servidor:
 *
 * <ul>
 *   <li><b>Webpay</b>, en el ambiente de integracion de Transbank por omision:
 *       el deudor paga con una tarjeta de prueba y ms-payments confirma la
 *       transaccion al volver.</li>
 *   <li><b>Khipu</b>, cuando hay {@code KHIPU_LLAVE}: el deudor paga con una
 *       transferencia, y se le pregunta a Khipu si esta conciliado.</li>
 *   <li><b>Mercado Pago</b>, cuando hay {@code MERCADOPAGO_ACCESS_TOKEN}: el
 *       deudor paga en Checkout Pro. No se espera su vuelta: se le pregunta a
 *       Mercado Pago por la preference abierta, que es lo que guarda que pagos
 *       se hicieron sobre ella.</li>
 * </ul>
 *
 * <p>Sin credenciales, las tres quedan simuladas: una pagina propia confirma
 * con la firma del enlace.
 */
@Service
public class PaymentService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ORDEN = Pattern.compile("^ORD(\\d{1,18})(T\\d+)?$");
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentEventRepository eventos;
    private final DebtNotificationRepository avisos;
    private final WebhookVerifier verifier;
    private final DebtClient deudas;
    private final UfService uf;
    private final KhipuClient khipu;
    private final FirmaDeKhipu firmaDeKhipu;
    private final WebpayClient webpay;
    private final MercadoPagoClient mercadopago;
    private final String publicUrl;
    private final String avisosDeKhipu;
    private final Duration venceEn;

    /** Donde Khipu avisa que un pago se concilio. Solo sirve con una direccion publica. */
    public static final String AVISOS_KHIPU = "/api/payments/public/khipu/aviso";

    /**
     * Donde Webpay devuelve al deudor despues de pagar o de anular. Va por la
     * direccion publica (el portal manda {@code /api} al gateway): el puerto
     * del gateway no esta publicado.
     */
    public static final String RETORNO_WEBPAY = "/api/payments/public/webpay/retorno";

    /** Donde Mercado Pago devuelve al deudor despues de pagar o cancelar. */
    public static final String RETORNO_MERCADOPAGO = "/api/payments/public/mercadopago/retorno";

    public PaymentService(
            PaymentRepository payments,
            PaymentEventRepository eventos,
            DebtNotificationRepository avisos,
            WebhookVerifier verifier,
            DebtClient deudas,
            UfService uf,
            KhipuClient khipu,
            FirmaDeKhipu firmaDeKhipu,
            WebpayClient webpay,
            MercadoPagoClient mercadopago,
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
        this.webpay = webpay;
        this.mercadopago = mercadopago;
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

        if (cobraWebpay(pago)) {
            //  La transaccion se abre en Transbank ahora; el token queda como
            //  el id del pago en la pasarela, que es como vuelve el deudor. Al
            //  deudor se lo lleva a una pagina propia que manda el token a
            //  Webpay por POST, como pide Transbank.
            WebpayCreateResponse transaccion = webpay.createTransaction(ordenDeCompra(pago),
                    "deuda-" + pago.getDebtId(), pago.getAmountClp(), publicUrl + RETORNO_WEBPAY);
            pago.setGatewayTxnId(transaccion.token());
            payments.save(pago);
            return respuesta(pago).conEnlaceDePago(
                    publicUrl + "/api/payments/public/" + pago.getId() + "/webpay?sig=" + firma(pago));
        }
        if (cobraKhipu(pago)) {
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
        if (cobraMercadoPago(pago)) {
            //  El cobro se abre en Mercado Pago ahora (Checkout Pro).
            String returnUrl = publicUrl + RETORNO_MERCADOPAGO;
            MercadoPagoClient.Preferencia pref = mercadopago.crearPreferencia(
                    String.valueOf(pago.getId()),
                    "Pago de deuda N° " + pago.getDebtId(),
                    pago.getAmountClp(),
                    user != null ? user.email() : null,
                    returnUrl
            );
            pago.setGatewayTxnId(pref.id());
            payments.save(pago);
            return respuesta(pago).conEnlaceDePago(pref.url(mercadopago.testMode()));
        }
        return respuesta(pago).conEnlaceDePago(enlaceDePago(pago));
    }

    private String enlaceDePago(Payment pago) {
        return publicUrl + "/pasarela/" + pago.getId() + "?sig=" + firma(pago);
    }

    private boolean cobraKhipu(Payment pago) {
        return pago.getGateway() == Payment.Gateway.khipu && khipu.real();
    }

    private boolean cobraWebpay(Payment pago) {
        return pago.getGateway() == Payment.Gateway.webpay && webpay.real();
    }

    private boolean cobraMercadoPago(Payment pago) {
        return pago.getGateway() == Payment.Gateway.mercadopago && mercadopago.real();
    }

    /** Si este pago lo cobra una pasarela real y no la simulacion. */
    private boolean cobraDeVerdad(Payment pago) {
        return cobraKhipu(pago) || cobraWebpay(pago) || cobraMercadoPago(pago);
    }

    private static String nombre(Payment pago) {
        return switch (pago.getGateway()) {
            case webpay -> "Webpay";
            case khipu -> "Khipu";
            case mercadopago -> "Mercado Pago";
        };
    }

    /**
     * La orden de compra en Transbank: con el id del pago adentro, que es como
     * se encuentra el pago cuando Webpay vuelve sin token (se acabo el tiempo).
     * La marca de tiempo la hace unica aunque la base se reinicie.
     */
    private static String ordenDeCompra(Payment pago) {
        return "ORD" + pago.getId() + "T" + (System.currentTimeMillis() % 100000);
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
        //  La confirmacion "a mano" es la de la pasarela simulada. Un pago real
        //  lo confirma solo su pasarela: si no, cualquiera con el enlace podria
        //  darlo por pagado sin pagar.
        if (cobraDeVerdad(pago)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Este pago lo confirma " + nombre(pago) + ", al terminar de pagar alla");
        }
        return confirmar(pago, PaymentEvent.Source.portal, null, null, null);
    }

    // ------------------------------------------------------------------
    //  Khipu
    // ------------------------------------------------------------------

    /**
     * Le pregunta a la pasarela en que va el pago. Lo llama la pagina del
     * resultado, a la que la pasarela devuelve al deudor: ni Khipu ni Mercado
     * Pago dicen nada al devolverlo, asi que hay que preguntar. Un pago
     * simulado o ya cerrado se devuelve tal cual.
     */
    @Transactional
    public PaymentResponse verificar(Long id, String sig) {
        Payment pago = conFirmaValida(id, sig);
        if (cobraKhipu(pago)) {
            conciliar(pago);
        }
        if (cobraMercadoPago(pago)) {
            conciliarMercadoPago(pago);
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
        if (cobraKhipu(pago)) {
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

    // ------------------------------------------------------------------
    //  Webpay
    // ------------------------------------------------------------------

    /**
     * La pagina que lleva al deudor a Webpay: un formulario POST con el token,
     * que se envia solo. Asi lo pide Transbank, y asi el enlace del cobro se
     * abre igual que el de las otras pasarelas, en una ventana aparte.
     */
    @Transactional(readOnly = true)
    public String paginaWebpay(Long id, String sig) {
        Payment pago = conFirmaValida(id, sig);
        if (!cobraWebpay(pago) || pago.getGatewayTxnId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Ese pago no se hace en Webpay");
        }
        if (pago.getStatus() != Payment.Status.created) {
            return redireccion(enlaceDePago(pago));
        }
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8"><title>Webpay</title></head>
                <body onload="document.forms[0].submit()">
                <form method="post" action="%s">
                <input type="hidden" name="token_ws" value="%s">
                <noscript><button type="submit">Ir a Webpay</button></noscript>
                </form>
                </body></html>
                """.formatted(html(webpay.paginaDePago()), html(pago.getGatewayTxnId()));
    }

    /**
     * Donde vuelve el deudor desde Webpay. Devuelve a donde mandarlo: la
     * pagina del resultado, en el portal.
     *
     * <ul>
     *   <li>Solo {@code token_ws}: pago. Se confirma la transaccion con
     *       Transbank y se aprueba si esta AUTHORIZED con codigo 0, el monto es
     *       el cobrado y la orden es la de este pago.</li>
     *   <li>{@code TBK_TOKEN} (con o sin {@code token_ws}): anulo, o hubo un
     *       error en el formulario de Webpay. No se confirma nada.</li>
     *   <li>Solo {@code TBK_ORDEN_COMPRA}: se le acabo el tiempo.</li>
     * </ul>
     *
     * <p>Volver dos veces con el mismo token no vuelve a confirmar: Transbank
     * lo rechazaria, y el pago ya quedo cerrado la primera vez.
     */
    @Transactional
    public String retornoWebpay(String tokenWs, String tbkToken, String ordenDeCompra) {
        boolean anulado = tbkToken != null && !tbkToken.isBlank();
        if (!anulado && tokenWs != null && !tokenWs.isBlank()) {
            Payment pago = porToken(tokenWs);
            if (pago.getStatus() == Payment.Status.created) {
                confirmarEnWebpay(pago, tokenWs.trim());
            }
            return enlaceDePago(pago);
        }
        Payment pago = anulado ? porToken(tbkToken) : porOrden(ordenDeCompra);
        if (pago.getStatus() == Payment.Status.created) {
            fallido(pago, null);
        }
        return enlaceDePago(pago);
    }

    private void confirmarEnWebpay(Payment pago, String token) {
        WebpayCommitResponse respuesta;
        try {
            respuesta = webpay.commitTransaction(token);
        } catch (ApiException transbankNoResponde) {
            //  No se sabe si quedo confirmada: el pago sigue abierto y, si nadie
            //  lo cierra, vence. Marcarlo fallido podria pisar un pago que otra
            //  vuelta del mismo deudor si alcanzo a confirmar.
            return;
        }
        String crudo = comoJson(respuesta);
        boolean montoCalza = respuesta.amount() != null && respuesta.amount().equals(pago.getAmountClp());
        boolean ordenCalza = pago.getId().equals(idDeLaOrden(respuesta.buyOrder()));
        if (respuesta.isAuthorized() && montoCalza && ordenCalza) {
            confirmar(pago, PaymentEvent.Source.webhook, token, null, crudo);
        } else {
            fallido(pago, crudo);
        }
    }

    /**
     * Los cobros de Webpay que nadie cerro: el deudor cerro la ventana sin
     * pagar ni anular, y Webpay nunca lo devolvio. Transbank anula el token a
     * los pocos minutos, asi que pasado el plazo no hay nada que confirmar:
     * quedan vencidos, y no como cobros abiertos para siempre.
     */
    @Transactional
    public int vencerWebpayAbandonados(Duration despuesDe) {
        if (!webpay.real()) {
            return 0;
        }
        Instant limite = Instant.now().minus(despuesDe);
        int vencidos = 0;
        for (Payment pago : payments.findByGatewayAndStatus(Payment.Gateway.webpay, Payment.Status.created)) {
            if (pago.getCreatedAt() != null && pago.getCreatedAt().isBefore(limite)) {
                pago.setStatus(Payment.Status.expired);
                payments.save(pago);
                eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.expired, PaymentEvent.Source.webhook));
                vencidos++;
            }
        }
        return vencidos;
    }

    // ------------------------------------------------------------------
    //  Mercado Pago
    // ------------------------------------------------------------------

    /**
     * Pregunta a Mercado Pago si el cobro de esta preferencia ya se pago.
     *
     * <p>Hace falta porque en local Mercado Pago no puede devolverse a un
     * {@code http://localhost} (descarta las back_urls que no son https) ni
     * avisar a la maquina del desarrollador. La preferencia es lo que guarda que
     * pagos se hicieron sobre ella, asi que se pregunta ahi y no se espera a que
     * el deudor vuelva.</p>
     *
     * <p>El estado no se toma de la respuesta: se usa el id del pago para
     * preguntarle a {@code /v1/payments}, que es la fuente que tambien usa el
     * aviso. Asi un pago de otra preferencia no puede cerrar este pago.</p>
     */
    private void conciliarMercadoPago(Payment pago) {
        if (pago.getStatus() != Payment.Status.created) {
            return;
        }
        //  Al abrirse el cobro se guardo el id de la preferencia; ya confirmado
        //  queda el id del pago, y entonces no hay nada que conciliar.
        String referencia = pago.getGatewayTxnId();
        if (referencia == null || referencia.isBlank()) {
            return;
        }
        try {
            MercadoPagoClient.EstadoPreferencia pref = mercadopago.consultarPreferencia(referencia.trim());
            if (pref == null || pref.pago() == null || pref.pago().id() == null) {
                return; //  Todavia no se ha pagado nada sobre esta preferencia.
            }
            MercadoPagoClient.PagoInfo info = mercadopago.consultarPago(String.valueOf(pref.pago().id()));
            String crudo = info.crudo() != null ? info.crudo().toString() : null;
            boolean esElMismo = String.valueOf(pago.getId()).equals(String.valueOf(info.externalReference()).trim())
                    && info.transactionAmount() != null
                    && info.transactionAmount().compareTo(BigDecimal.valueOf(pago.getAmountClp())) == 0;
            if (info.pagado() && esElMismo) {
                confirmar(pago, PaymentEvent.Source.webhook, String.valueOf(info.id()), null, crudo);
            } else if (info.pagado()) {
                //  Pagado, pero no es el cobro que se abrio: no se acepta.
                log.warn("Mercado Pago pago {} del pago {} no calza con la preferencia abierta", info.id(), pago.getId());
                fallido(pago, crudo);
            } else if ("rejected".equalsIgnoreCase(info.status()) || "cancelled".equalsIgnoreCase(info.status())) {
                fallido(pago, crudo);
            }
        } catch (ApiException e) {
            //  Mercado Pago no respondio: se deja el pago abierto y se reintenta
            //  en la proxima consulta de la pagina del resultado.
            log.warn("No se pudo conciliar el pago {} con Mercado Pago: {}", pago.getId(), e.getMessage());
        }
    }

    /**
     * Donde vuelve el deudor desde Mercado Pago tras pagar o cancelar.
     * Se consulta el pago si hay paymentId o se usa el estado que viene de vuelta.
     */
    @Transactional
    public String retornoMercadoPago(String paymentId, String status, String collectionStatus,
                                     String externalReference, String preferenceId) {
        Payment pago = null;
        if (externalReference != null && !externalReference.isBlank()) {
            try {
                pago = payments.findById(Long.parseLong(externalReference.trim())).orElse(null);
            } catch (NumberFormatException ignored) {}
        }
        if (pago == null && preferenceId != null && !preferenceId.isBlank()) {
            pago = payments.findByGatewayAndGatewayTxnId(Payment.Gateway.mercadopago, preferenceId.trim()).orElse(null);
        }
        if (pago == null && paymentId != null && !paymentId.isBlank() && !"null".equalsIgnoreCase(paymentId)) {
            pago = payments.findByGatewayAndGatewayTxnId(Payment.Gateway.mercadopago, paymentId.trim()).orElse(null);
        }
        if (pago == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Mercado Pago devolvio un pago que no existe");
        }

        String actualStatus = (status != null && !status.isBlank() && !"null".equalsIgnoreCase(status))
                ? status.trim()
                : (collectionStatus != null && !collectionStatus.isBlank() && !"null".equalsIgnoreCase(collectionStatus))
                ? collectionStatus.trim()
                : null;

        if (pago.getStatus() == Payment.Status.created) {
            if (paymentId != null && !paymentId.isBlank() && !"null".equalsIgnoreCase(paymentId)) {
                try {
                    MercadoPagoClient.PagoInfo info = mercadopago.consultarPago(paymentId.trim());
                    String crudo = info.crudo() != null ? info.crudo().toString() : null;
                    if (info.pagado()) {
                        confirmar(pago, PaymentEvent.Source.webhook, paymentId.trim(), null, crudo);
                    } else if ("rejected".equalsIgnoreCase(info.status()) || "cancelled".equalsIgnoreCase(info.status())) {
                        fallido(pago, crudo);
                    }
                } catch (Exception e) {
                    if ("approved".equalsIgnoreCase(actualStatus)) {
                        confirmar(pago, PaymentEvent.Source.portal, paymentId.trim(), null, null);
                    } else if (actualStatus != null) {
                        fallido(pago, null);
                    }
                }
            } else if ("approved".equalsIgnoreCase(actualStatus)) {
                confirmar(pago, PaymentEvent.Source.portal, preferenceId, null, null);
            } else if ("rejected".equalsIgnoreCase(actualStatus) || "cancelled".equalsIgnoreCase(actualStatus)) {
                fallido(pago, null);
            }
        }

        return enlaceDePago(pago);
    }

    /**
     * Procesa avisos asíncronos (Webhooks / IPN) de Mercado Pago.
     */
    @Transactional
    public void avisoMercadoPago(String topic, String idParam, String cuerpo) {
        String paymentId = idParam;
        if ((paymentId == null || paymentId.isBlank()) && cuerpo != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode nodo = JSON.readTree(cuerpo);
                if (nodo.hasNonNull("data") && nodo.get("data").hasNonNull("id")) {
                    paymentId = nodo.get("data").get("id").asText();
                } else if (nodo.hasNonNull("id")) {
                    paymentId = nodo.get("id").asText();
                }
            } catch (Exception ignored) {}
        }
        if (paymentId != null && !paymentId.isBlank()) {
            try {
                MercadoPagoClient.PagoInfo info = mercadopago.consultarPago(paymentId.trim());
                if (info.externalReference() != null) {
                    Payment pago = payments.findById(Long.parseLong(info.externalReference().trim())).orElse(null);
                    if (pago != null && pago.getStatus() == Payment.Status.created) {
                        String crudo = info.crudo() != null ? info.crudo().toString() : null;
                        if (info.pagado()) {
                            confirmar(pago, PaymentEvent.Source.webhook, paymentId.trim(), null, crudo);
                        } else if ("rejected".equalsIgnoreCase(info.status()) || "cancelled".equalsIgnoreCase(info.status())) {
                            fallido(pago, crudo);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private Payment porToken(String token) {
        return payments.findByGatewayAndGatewayTxnId(Payment.Gateway.webpay, token.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Webpay devolvio un pago que no existe"));
    }

    private Payment porOrden(String ordenDeCompra) {
        Long id = idDeLaOrden(ordenDeCompra);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Webpay no dijo que pago era");
        }
        Payment pago = buscar(id);
        if (pago.getGateway() != Payment.Gateway.webpay) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Webpay devolvio un pago que no existe");
        }
        return pago;
    }

    /** El id del pago dentro de la orden de compra ({@code ORD41T12345}), o null. */
    private static Long idDeLaOrden(String ordenDeCompra) {
        Matcher m = ORDEN.matcher(ordenDeCompra == null ? "" : ordenDeCompra.trim());
        return m.matches() ? Long.valueOf(m.group(1)) : null;
    }

    private static String comoJson(Object respuesta) {
        try {
            return JSON.writeValueAsString(respuesta);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String redireccion(String url) {
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=%s"></head>
                <body><a href="%s">Ver el resultado del pago</a></body></html>
                """.formatted(html(url), html(url));
    }

    private static String html(String texto) {
        return texto.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
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
            throw new ApiException(HttpStatus.CONFLICT, "Este pago lo confirma " + nombre(pago));
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
