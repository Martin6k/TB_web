package com.tbridge.debt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.evento.CampanaAvanceDatos;
import com.tbridge.debt.dto.evento.EventoV1;
import com.tbridge.debt.dto.request.SuscripcionRequest;
import com.tbridge.debt.dto.response.SuscripcionResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.OutboxEvent;
import com.tbridge.debt.model.Subscription;
import com.tbridge.debt.repository.OutboxEventRepository;
import com.tbridge.debt.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contrato 3 — Eventos v1: lo que DataBridge le avisa a quien le entrego la
 * cartera.
 *
 * <p><b>A quien.</b> Al emisor del ultimo lote en que vino la deuda: la
 * agencia si la cartera llego por APOFYX, el acreedor si trabaja directo.
 * DataBridge nunca le habla al acreedor por encima de su agencia; es APOFYX
 * la que le reporta a Patrimonio.
 *
 * <p><b>Cuando.</b> En la misma transaccion que produce el hecho. El evento
 * queda en la bandeja y lo entrega {@link EventDispatcher}; si el receptor
 * esta caido, el hecho igual ocurre y el aviso espera.
 *
 * <p><b>Que lleva.</b> Ids y montos, nunca datos personales (decision I5).
 */
@Service
public class EventosService {

    public static final String PAGO_CONFIRMADO = "pago.confirmado";
    public static final String DEUDA_SALDADA = "deuda.saldada";
    public static final String REPACTACION_ACEPTADA = "repactacion.aceptada";
    public static final String DEUDA_RETIRADA = "deuda.retirada";
    public static final String DEUDA_DISPUTADA = "deuda.disputada";
    public static final String DEUDA_REANUDADA = "deuda.reanudada";
    public static final String LOTE_PROCESADO = "lote.procesado";
    public static final String CAMPANA_AVANCE = "campana.avance";

    /** El catalogo del contrato (seccion 8.2): todo se emite. */
    static final Set<String> CATALOGO = Set.of(
            LOTE_PROCESADO, CAMPANA_AVANCE, REPACTACION_ACEPTADA, PAGO_CONFIRMADO,
            DEUDA_SALDADA, DEUDA_DISPUTADA, DEUDA_REANUDADA, DEUDA_RETIRADA);

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final SecureRandom AZAR = new SecureRandom();

    private final SubscriptionRepository suscripciones;
    private final OutboxEventRepository bandeja;
    private final ObjectMapper json;

    public EventosService(SubscriptionRepository suscripciones, OutboxEventRepository bandeja, ObjectMapper json) {
        this.suscripciones = suscripciones;
        this.bandeja = bandeja;
        this.json = json;
    }

    // ------------------------------------------------------------------
    //  Publicar
    // ------------------------------------------------------------------

    /**
     * Un evento de una deuda para cada suscriptor de quien la entrego. Sin
     * suscriptores no hace nada: DataBridge funciona igual sin nadie
     * escuchando. Los datos empiezan con {@code deuda_id_externo}.
     */
    @Transactional
    public int publicar(Debt deuda, String tipo, Object datos, Instant ocurrido) {
        return anotar(deuda.getLastBatch().getSender(), deuda.getCreditor(),
                deuda.getLastBatch().getExternalId(), tipo, datos, ocurrido);
    }

    /** Un evento del lote, no de una deuda. */
    @Transactional
    public int publicarDeLote(Batch lote, String tipo, Object datos, Instant ocurrido) {
        return anotar(lote.getSender(), lote.getCreditor(), lote.getExternalId(), tipo, datos, ocurrido);
    }

    /**
     * El avance de una campana. No nace de un lote, asi que su sobre no lleva
     * {@code lote_id_externo}: la campana se identifica en los datos.
     */
    @Transactional
    public int publicarDeCampana(Campaign campana, CampanaAvanceDatos datos, Instant ocurrido) {
        return anotar(campana.getAgency(), campana.getCreditor(), null, CAMPANA_AVANCE, datos, ocurrido);
    }

    private int anotar(Organization destinatario, Organization acreedor, String lote,
                       String tipo, Object datos, Instant ocurrido) {
        List<Subscription> interesados = suscripciones.findByOrganizationAndActiveTrue(destinatario)
                .stream().filter(s -> quiere(s, tipo)).toList();
        if (interesados.isEmpty()) {
            return 0;
        }

        String id = UUID.randomUUID().toString();
        String cuerpo = aTexto(new EventoV1("evt_" + id, tipo, "1", enChile(ocurrido), acreedor.getRut(), lote, datos));
        for (Subscription suscripcion : interesados) {
            bandeja.save(OutboxEvent.para(suscripcion, id, tipo, cuerpo, ocurrido));
        }
        return interesados.size();
    }

    /** Fecha y hora con el desfase de Chile, como pide el contrato: 2026-09-20T14:03:11-03:00. */
    public static String enChile(Instant momento) {
        return momento.truncatedTo(ChronoUnit.SECONDS).atZone(CHILE).toOffsetDateTime().toString();
    }

    /** Los pesos viajan enteros; la UF, con sus decimales (contrato seccion 5). */
    public static Number monto(BigDecimal valor, Debt.Currency moneda) {
        if (valor == null) {
            return null;
        }
        return moneda == Debt.Currency.CLP ? valor.longValue() : valor.stripTrailingZeros();
    }

    private boolean quiere(Subscription suscripcion, String tipo) {
        if (suscripcion.getEvents() == null || suscripcion.getEvents().isBlank()) {
            return true;
        }
        try {
            JsonNode tipos = json.readTree(suscripcion.getEvents());
            if (!tipos.isArray() || tipos.isEmpty()) {
                return true;
            }
            for (JsonNode t : tipos) {
                if (tipo.equals(t.asText())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    //  Suscribirse
    // ------------------------------------------------------------------

    /**
     * Registra la URL a la que una organizacion quiere recibir sus eventos.
     *
     * <p>Es idempotente: registrar la misma URL otra vez la reactiva y
     * devuelve el mismo secreto, asi el receptor puede repetir la llamada sin
     * invalidar lo que ya tiene configurado.
     */
    @Transactional
    public SuscripcionResponse suscribir(Organization organizacion, SuscripcionRequest pedido) {
        String url = pedido.url() == null ? "" : pedido.url().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new CarteraInvalida("url_invalida", "La URL tiene que empezar con http:// o https://");
        }
        if (url.length() > 300) {
            throw new CarteraInvalida("url_invalida", "La URL no puede pasar de 300 caracteres");
        }

        List<String> tipos = new ArrayList<>();
        for (String tipo : pedido.eventos() == null ? List.<String>of() : pedido.eventos()) {
            if (!CATALOGO.contains(tipo)) {
                throw new CarteraInvalida("evento_desconocido", "No existe el evento '" + tipo + "' en el contrato v1");
            }
            tipos.add(tipo);
        }

        Subscription suscripcion = suscripciones.findByOrganizationAndUrl(organizacion, url)
                .orElseGet(() -> {
                    Subscription nueva = new Subscription();
                    nueva.setOrganization(organizacion);
                    nueva.setUrl(url);
                    nueva.setSecret(nuevoSecreto());
                    return nueva;
                });
        suscripcion.setEvents(tipos.isEmpty() ? null : aTexto(tipos));
        suscripcion.setActive(true);
        suscripciones.save(suscripcion);

        return new SuscripcionResponse(url, tipos.isEmpty() ? "todos" : tipos, suscripcion.getSecret(),
                "X-Firma: v1=HMAC-SHA256(secreto, X-Timestamp + \".\" + cuerpo), en hex");
    }

    private static String nuevoSecreto() {
        byte[] azar = new byte[32];
        AZAR.nextBytes(azar);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(azar);
    }

    private String aTexto(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento", e);
        }
    }
}
