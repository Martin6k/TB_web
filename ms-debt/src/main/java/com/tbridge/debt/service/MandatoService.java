package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tbridge.common.util.Rut;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.request.MandatoRequest;
import com.tbridge.debt.dto.response.CampanaResponse;
import com.tbridge.debt.dto.response.MandatoResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Contrato 2: el mandato y la campana.
 *
 * <p>Lo que la agencia le dice a DataBridge antes de pasarle una cartera. No
 * lleva ningun dato personal: es quien cobra por cuenta de quien, y con que
 * estrategia.
 */
@Service
public class MandatoService {

    private final OrganizationRepository organizations;
    private final MandateRepository mandates;
    private final CampaignRepository campaigns;

    public MandatoService(OrganizationRepository organizations, MandateRepository mandates,
                          CampaignRepository campaigns) {
        this.organizations = organizations;
        this.mandates = mandates;
        this.campaigns = campaigns;
    }

    /**
     * La agencia declara que cobra por cuenta de un acreedor.
     *
     * <p><b>La agencia responde por el mandato:</b> es su contrato con el
     * acreedor. DataBridge registra quien lo declaro y cuando, que es lo que
     * permite reconstruir despues con que autorizacion se cobro.
     *
     * <p>Por lo mismo, <b>un acreedor que DataBridge no conoce se registra aca</b>,
     * con los nombres que manda la agencia. Asi una empresa nueva de la agencia
     * entra sin que nadie la cargue a mano en DataBridge.
     */
    @Transactional
    public MandatoResponse registrarMandato(Organization agencia, MandatoRequest pedido) {
        if (!agencia.esAgencia()) {
            throw new CarteraInvalida("no_es_agencia",
                    "Esa organizacion no esta registrada como agencia de cobranza", 403);
        }
        Organization acreedor = buscarORegistrarAcreedor(pedido);
        if (acreedor.getId().equals(agencia.getId())) {
            throw new CarteraInvalida("mandato_invalido", "Una agencia no puede tener mandato sobre si misma");
        }

        LocalDate desde = fecha(pedido.vigenteDesde(), LocalDate.now());

        //  Declarar dos veces el mismo mandato no es un error: es un reintento.
        //  Se devuelve el que ya existe en vez de chocar contra el indice unico.
        Mandate existente = mandates.findByAgencyAndCreditorAndStatus(agencia, acreedor, Mandate.Status.active)
                .stream()
                .filter(m -> m.getValidFrom().equals(desde))
                .findFirst()
                .orElse(null);
        if (existente != null) {
            return MandatoResponse.from(existente);
        }

        Mandate mandato = new Mandate();
        mandato.setAgency(agencia);
        mandato.setCreditor(acreedor);
        mandato.setValidFrom(desde);
        if (pedido.vigenteHasta() != null) {
            mandato.setValidTo(fecha(pedido.vigenteHasta(), null));
        }
        if (pedido.moraMaximaDias() != null) {
            mandato.setMaxOverdueDays(pedido.moraMaximaDias().shortValue());
        }
        mandato.setDeclaredBy(agencia.getTradeName());
        mandates.save(mandato);
        return MandatoResponse.from(mandato);
    }

    @Transactional
    public CampanaResponse registrarCampana(Organization agencia, CampanaRequest pedido) {
        Organization acreedor = buscarAcreedor(pedido.acreedorRut());
        String idExterno = pedido.idExterno();
        if (idExterno == null || idExterno.isBlank()) {
            throw new CarteraInvalida("campana_incompleta", "La campana necesita id_externo");
        }
        if (mandates.findByAgencyAndCreditorAndStatus(agencia, acreedor, Mandate.Status.active).isEmpty()) {
            throw new CarteraInvalida("sin_mandato", "No hay mandato vigente sobre ese acreedor", 403);
        }

        Campaign campana = campaigns.findByAgencyAndExternalId(agencia, idExterno).orElseGet(Campaign::new);
        campana.setAgency(agencia);
        campana.setCreditor(acreedor);
        campana.setExternalId(idExterno);
        campana.setName(pedido.nombre() == null ? idExterno : pedido.nombre());
        campana.setStartsOn(fecha(pedido.inicio(), LocalDate.now()));
        if (pedido.fin() != null) {
            campana.setEndsOn(fecha(pedido.fin(), null));
        }
        if (presente(pedido.canales())) {
            campana.setChannels(pedido.canales().toString());
        }
        if (pedido.intentos() != null) {
            campana.setAttempts(pedido.intentos().shortValue());
        }
        if (presente(pedido.cadenciaDias())) {
            campana.setCadenceDays(pedido.cadenciaDias().toString());
        }
        campaigns.save(campana);
        return CampanaResponse.from(campana);
    }

    private Organization buscarAcreedor(String crudo) {
        String rut = rutDelAcreedor(crudo);
        return organizations.findByRut(rut).orElseThrow(() -> new CarteraInvalida(
                "acreedor_desconocido", "El acreedor " + rut + " no esta registrado", 404));
    }

    /**
     * El acreedor del mandato, registrado si es nuevo. Uno que ya existe no se
     * toca: sus nombres los fijo quien lo registro primero, y un mandato de otra
     * agencia no los puede cambiar.
     */
    private Organization buscarORegistrarAcreedor(MandatoRequest pedido) {
        String rut = rutDelAcreedor(pedido.acreedorRut());
        return organizations.findByRut(rut).orElseGet(() -> {
            String razonSocial = texto(pedido.razonSocial(), rut, 160);
            Organization nueva = new Organization();
            nueva.setRut(rut);
            nueva.setLegalName(razonSocial);
            nueva.setTradeName(texto(pedido.nombreFantasia(), razonSocial, 120));
            nueva.setKind(Organization.Kind.creditor);
            return organizations.save(nueva);
        });
    }

    private static String rutDelAcreedor(String crudo) {
        String rut = Rut.normalizar(crudo);
        if (!Rut.esValido(rut)) {
            throw new CarteraInvalida("acreedor_invalido", "El RUT del acreedor no es valido");
        }
        return rut;
    }

    /** El texto recortado a lo que cabe en la columna, o el valor por omision si viene vacio. */
    private static String texto(String valor, String porOmision, int largo) {
        String limpio = valor == null || valor.isBlank() ? porOmision : valor.strip();
        return limpio.length() > largo ? limpio.substring(0, largo) : limpio;
    }

    private static boolean presente(JsonNode nodo) {
        return nodo != null && !nodo.isNull() && !nodo.isMissingNode();
    }

    /** Una fecha que no se entiende toma el valor por omision: asi lo define la version 1 del contrato. */
    private static LocalDate fecha(String texto, LocalDate porDefecto) {
        try {
            return LocalDate.parse(texto);
        } catch (Exception e) {
            return porDefecto;
        }
    }
}
