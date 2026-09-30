package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Una empresa: acreedor, agencia de cobranza, o las dos cosas.
 *
 * <p>Van en la misma tabla porque para DataBridge son lo mismo: una empresa
 * con RUT que se autentica y opera. Lo que cambia es el rol.
 *
 * <p>El RUT es la identidad que sirve fuera de aqui: es con lo que APOFYX la
 * busca en su CRM y con lo que se le atribuyen los pagos.
 */
@Entity
@Table(name = "organizations")
public class Organization {

    public enum Kind { creditor, agency, both }

    public enum Status { active, suspended }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12, unique = true)
    private String rut;

    @Column(name = "legal_name", nullable = false, length = 160)
    private String legalName;

    @Column(name = "trade_name", nullable = false, length = 120)
    private String tradeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind = Kind.creditor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean esAgencia() {
        return kind == Kind.agency || kind == Kind.both;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRut() { return rut; }
    public void setRut(String rut) { this.rut = rut; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getTradeName() { return tradeName; }
    public void setTradeName(String tradeName) { this.tradeName = tradeName; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
