package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Cada Cartera v1 recibida.
 *
 * <p>`sender` es quien la envio —el acreedor mismo o su agencia— y `creditor`
 * de quien es la deuda. Se separan porque la idempotencia es por EMISOR: dos
 * emisores distintos pueden numerar sus lotes igual sin chocar.
 *
 * <p>`payloadHash` distingue un reenvio identico, al que se le responde lo
 * mismo, de un lote con el mismo id y otro contenido, que se rechaza.
 */
@Entity
@Table(name = "batches")
public class Batch {

    public enum Source { api, file }

    public enum Status { processed, rejected }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private Organization sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creditor_id", nullable = false)
    private Organization creditor;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Column(name = "cut_off", nullable = false)
    private LocalDate cutOff;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source = Source.api;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.processed;

    @Column(name = "received_count", nullable = false)
    private Integer receivedCount = 0;

    @Column(name = "accepted_count", nullable = false)
    private Integer acceptedCount = 0;

    @Column(name = "rejected_count", nullable = false)
    private Integer rejectedCount = 0;

    //  CHAR(64) y no VARCHAR: un SHA-256 en hexadecimal mide siempre 64.
    @Column(name = "payload_hash", nullable = false, columnDefinition = "char(64)")
    private String payloadHash;

    @Column(nullable = false, columnDefinition = "json")
    private String response = "{}";

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Organization getSender() { return sender; }
    public void setSender(Organization sender) { this.sender = sender; }
    public Organization getCreditor() { return creditor; }
    public void setCreditor(Organization creditor) { this.creditor = creditor; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public void setCutOff(LocalDate cutOff) { this.cutOff = cutOff; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Integer getReceivedCount() { return receivedCount; }
    public void setReceivedCount(Integer receivedCount) { this.receivedCount = receivedCount; }
    public Integer getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(Integer acceptedCount) { this.acceptedCount = acceptedCount; }
    public Integer getRejectedCount() { return rejectedCount; }
    public void setRejectedCount(Integer rejectedCount) { this.rejectedCount = rejectedCount; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
