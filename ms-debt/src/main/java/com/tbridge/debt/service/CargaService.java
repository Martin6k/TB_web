package com.tbridge.debt.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.OpcionesCargaResponse;
import com.tbridge.debt.dto.response.OpcionesCargaResponse.AcreedorCarga;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.MandateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * La carga de cartera por archivo, desde el portal: el "modo archivo" del
 * contrato (seccion 9) para quien no tiene integracion por API.
 *
 * <p>El archivo no tiene una ingesta propia. Se convierte a Cartera v1 y entra
 * por {@link CarteraIntakeService}, igual que lo que llega por API. Quien
 * carga es la organizacion de la sesion, nunca un RUT que venga en el
 * formulario.
 */
@Service
@Transactional(readOnly = true)
public class CargaService {

    private final CarteraIntakeService ingesta;
    private final DebtService debts;
    private final MandateRepository mandatos;
    private final CampaignRepository campanas;

    public CargaService(CarteraIntakeService ingesta, DebtService debts, MandateRepository mandatos,
                        CampaignRepository campanas) {
        this.ingesta = ingesta;
        this.debts = debts;
        this.mandatos = mandatos;
        this.campanas = campanas;
    }

    /**
     * Por cuenta de quien puede cargar la empresa de la sesion: la propia, si
     * es acreedora, y la de cada acreedor que le dio un mandato vigente, con
     * sus campanas.
     */
    public OpcionesCargaResponse opciones(JwtPrincipal user) {
        Organization organizacion = empresaDe(user);
        List<AcreedorCarga> acreedores = new ArrayList<>();
        if (organizacion.getKind() != Organization.Kind.agency) {
            acreedores.add(AcreedorCarga.de(organizacion, List.of()));
        }
        LocalDate hoy = LocalDate.now();
        for (Mandate mandato : mandatos.findByAgencyAndStatus(organizacion, Mandate.Status.active)) {
            if (mandato.vigenteAl(hoy)) {
                acreedores.add(AcreedorCarga.de(mandato.getCreditor(),
                        campanas.findByAgencyAndCreditorOrderByStartsOnDesc(organizacion, mandato.getCreditor())));
            }
        }
        return new OpcionesCargaResponse(organizacion.getTradeName(), acreedores, ingesta.minDiasMora());
    }

    @Transactional
    public CarteraResponse subir(JwtPrincipal user, byte[] archivo, String loteId, String fechaCorte,
                                 String acreedorRut, String campana) {
        Organization organizacion = empresaDe(user);
        boolean propia = organizacion.getRut().equalsIgnoreCase(acreedorRut);
        CarteraCsv.Lote lote = new CarteraCsv.Lote(loteId, fechaCorte, acreedorRut,
                propia ? null : organizacion.getRut(), propia ? null : campana);
        return ingesta.recibir(organizacion, CarteraCsv.leer(archivo, lote), Batch.Source.file);
    }

    private Organization empresaDe(JwtPrincipal user) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo una empresa carga cartera");
        }
        return debts.organizacionDe(user);
    }
}
