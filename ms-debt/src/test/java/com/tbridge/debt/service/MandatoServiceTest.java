package com.tbridge.debt.service;

import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.request.MandatoRequest;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Una empresa nueva de la agencia llega a DataBridge con el mandato: nadie la
 * tiene que cargar a mano antes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MandatoServiceTest {

    @Mock private OrganizationRepository organizations;
    @Mock private MandateRepository mandates;
    @Mock private CampaignRepository campaigns;

    private MandatoService servicio;
    private Organization apofyx;

    @BeforeEach
    void preparar() {
        servicio = new MandatoService(organizations, mandates, campaigns);
        apofyx = new Organization();
        apofyx.setId(1L);
        apofyx.setRut("77305118-6");
        apofyx.setTradeName("APOFYX");
        apofyx.setKind(Organization.Kind.agency);

        when(organizations.findByRut(any())).thenReturn(Optional.empty());
        when(organizations.save(any(Organization.class))).thenAnswer(llamada -> {
            Organization guardada = llamada.getArgument(0);
            guardada.setId(2L);
            return guardada;
        });
        when(mandates.save(any(Mandate.class))).thenAnswer(llamada -> llamada.getArgument(0));
    }

    private static MandatoRequest mandato(String rut, String razonSocial, String nombre) {
        return new MandatoRequest(rut, razonSocial, nombre, "2026-09-01", null, 120);
    }

    @Test
    void un_acreedor_que_databridge_no_conoce_se_registra_con_el_mandato() {
        servicio.registrarMandato(apofyx, mandato("76.418.902-7", "Patrimonio Inmuebles SpA", "Patrimonio Inmuebles"));

        ArgumentCaptor<Organization> nueva = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(nueva.capture());
        assertEquals("76418902-7", nueva.getValue().getRut());
        assertEquals("Patrimonio Inmuebles SpA", nueva.getValue().getLegalName());
        assertEquals("Patrimonio Inmuebles", nueva.getValue().getTradeName());
        assertEquals(Organization.Kind.creditor, nueva.getValue().getKind());
    }

    @Test
    void sin_nombres_el_acreedor_queda_con_su_rut() {
        servicio.registrarMandato(apofyx, mandato("76418902-7", null, " "));

        ArgumentCaptor<Organization> nueva = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(nueva.capture());
        assertEquals("76418902-7", nueva.getValue().getLegalName());
        assertEquals("76418902-7", nueva.getValue().getTradeName());
    }

    @Test
    void un_acreedor_que_ya_existe_no_se_toca() {
        Organization patrimonio = new Organization();
        patrimonio.setId(2L);
        patrimonio.setRut("76418902-7");
        patrimonio.setLegalName("Patrimonio Inmuebles SpA");
        patrimonio.setTradeName("Patrimonio Inmuebles");
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));

        servicio.registrarMandato(apofyx, mandato("76418902-7", "Otro nombre SpA", "Otro"));

        verify(organizations, never()).save(any());
        assertEquals("Patrimonio Inmuebles", patrimonio.getTradeName());
    }

    @Test
    void un_rut_invalido_no_registra_a_nadie() {
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarMandato(apofyx, mandato("76418902-0", "Mal escrito SpA", null)));

        assertEquals("acreedor_invalido", fallo.getCodigo());
        verify(organizations, never()).save(any());
    }

    @Test
    void una_campana_sin_mandato_previo_sigue_sin_acreedor() {
        CampanaRequest campana = new CampanaRequest("APX-CMP-9", "76418902-7", "Arriendos", "2026-09-19",
                null, null, null, null);

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, campana));

        assertEquals("acreedor_desconocido", fallo.getCodigo());
        assertEquals(404, fallo.getStatus());
    }
}
