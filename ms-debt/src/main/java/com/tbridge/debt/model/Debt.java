package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lo que un deudor le debe a un acreedor.
 *
 * <p><b>`creditor` es la columna que arregla la fuga del modelo anterior.</b>
 * Antes cualquier acreedor veia las deudas de todos, y no era un descuido del
 * codigo: no existia el campo por el que filtrar. Ahora existe, y toda
 * consulta de acreedor pasa por el.
 *
 * <p>`externalId` es el id que le puso el acreedor y viaja intacto por toda la
 * cadena: es lo que permite que un pago vuelva hasta la obligacion que lo
 * origino en el sistema del acreedor (un contrato, una matricula, un presupuesto).
 */
@Entity
@Table(name = "debts")
public class Debt {

    public enum Currency { CLP, UF }

    public enum Status { open, repacted, paid, withdrawn, disputed }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creditor_id", nullable = false)
    private Organization creditor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debtor_id", nullable = false)
    private Debtor debtor;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency = Currency.CLP;

    @Column(nullable = false, length = 200)
    private String concept;

    /** Lo que se le muestra al deudor para que reconozca la deuda. */
    @Column(columnDefinition = "json")
    private String refs;

    @Column(name = "original_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal originalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.open;

    /*
      * El mandato que autoriza la cobranza y la campana en que se esta
      * cobrando. Las columnas existian desde el primer esquema, pero la
      * entidad no las mapeaba: la campana llegaba en la cartera y se perdia,
      * y sin ella no habia forma de medir una campana ni de contactar por
      * campana.
      */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mandate_id")
    private Mandate mandate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_batch_id", nullable = false)
    private Batch firstBatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_batch_id", nullable = false)
    private Batch lastBatch;

    @Column(name = "withdrawn_reason", length = 30)
    private String withdrawnReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Organization getCreditor() { return creditor; }
    public void setCreditor(Organization creditor) { this.creditor = creditor; }
    public Debtor getDebtor() { return debtor; }
    public void setDebtor(Debtor debtor) { this.debtor = debtor; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public Currency getCurrency() { return currency; }
    public void setCurrency(Currency currency) { this.currency = currency; }
    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }
    public void setRefs(String refs) { this.refs = refs; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public void setMandate(Mandate mandate) { this.mandate = mandate; }
    public void setCampaign(Campaign campaign) { this.campaign = campaign; }
    public void setFirstBatch(Batch firstBatch) { this.firstBatch = firstBatch; }
    public Batch getLastBatch() { return lastBatch; }
    public void setLastBatch(Batch lastBatch) { this.lastBatch = lastBatch; }
    public void setWithdrawnReason(String withdrawnReason) { this.withdrawnReason = withdrawnReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
