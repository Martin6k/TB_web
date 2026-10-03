package com.tbridge.debt.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A donde se le avisan los eventos a una organizacion.
 *
 * <p>La agencia (o el acreedor que trabaja directo) registra su URL y recibe
 * un secreto. Con ese secreto DataBridge firma cada aviso, y el receptor
 * descarta lo que no calce.
 *
 * <p>El secreto no se guarda como huella porque hay que firmar con el, no
 * compararlo. Va cifrado ({@link SecretoCifrado}), con una llave fuera de la
 * base.
 */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 300)
    private String url;

    @Convert(converter = SecretoCifrado.class)
    @Column(nullable = false, length = 255)
    private String secret;

    /** Los tipos que quiere recibir. Vacio = todos. */
    @Column(columnDefinition = "json")
    private String events;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getEvents() { return events; }
    public void setEvents(String events) { this.events = events; }
    public void setActive(Boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
