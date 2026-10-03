package com.tbridge.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * El libro del pago. Solo se inserta; nada se edita ni se borra.
 *
 * <p>Antes el estado se sobreescribia en la fila del pago, asi que al final
 * solo se sabia como termino, nunca como llego ahi. Con esto se puede
 * responder "cuantos pagos se autorizaron y despues fallaron", que antes era
 * imposible.
 *
 * <p><b>signatureOk</b> guarda si la firma del webhook estaba bien. Un aviso
 * con firma invalida NO se aplica, pero se guarda igual: alguien mandando
 * avisos falsos es algo que hay que poder ver.
 *
 * <p><b>gatewayPayload</b> guarda la respuesta cruda de una pasarela real
 * (lo que dijo Khipu del pago), tal como llego: es lo que se mira al conciliar
 * con la cartola. Con las pasarelas simuladas queda en NULL.
 */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    public enum Type { created, authorized, paid, failed, expired, refunded }

    public enum Source { portal, webhook, manual }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    @Column(name = "signature_ok")
    private Boolean signatureOk;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gateway_payload")
    private String gatewayPayload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public static PaymentEvent de(Long paymentId, Type type, Source source) {
        PaymentEvent evento = new PaymentEvent();
        evento.paymentId = paymentId;
        evento.type = type;
        evento.source = source;
        return evento;
    }

    /**
     * Si la firma del aviso era valida.
     *
     * Recibe Boolean y no boolean a proposito: un pago confirmado desde el
     * portal no tiene firma de pasarela que revisar, y eso es null, que no es
     * lo mismo que "firma invalida".
     */
    public PaymentEvent conFirma(Boolean valida) {
        this.signatureOk = valida;
        return this;
    }

    /** La respuesta de la pasarela, en JSON. */
    public PaymentEvent conRespuesta(String json) {
        this.gatewayPayload = json;
        return this;
    }

    public String getGatewayPayload() {
        return gatewayPayload;
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Boolean getSignatureOk() {
        return signatureOk;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
