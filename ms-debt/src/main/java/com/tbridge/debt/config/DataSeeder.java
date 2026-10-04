package com.tbridge.debt.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtCharge;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.DetallePago;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.BatchRepository;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.repository.RepactationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los datos de la demo: la cartera que APOFYX le entrego a DataBridge por
 * cuenta de tres acreedores de rubros distintos, con cada deudor en una
 * situacion distinta. DataBridge no es de un rubro: cobra lo que le entregue
 * la agencia, sea un arriendo, un arancel o un tratamiento dental.
 *
 * <p>Es la misma historia que cuentan los datos de ejemplo de Patrimonio y de
 * APOFYX, vista desde aca. DataBridge cobra desde 30 dias de mora, contados
 * desde el cargo impago mas antiguo.
 *
 * <p><b>Patrimonio Inmuebles</b> (arriendos) entrego dos carteras: la del
 * corte del 18 de agosto y la del 18 de septiembre. En agosto DataBridge
 * rechazo a Felipe y a Carolina (debian solo agosto: 13 dias), y en
 * septiembre a Valentina (debe solo septiembre: 13 dias). Lo que paso despues:
 *
 * <ul>
 *   <li><b>Felipe</b> acepto un convenio de 6 cuotas y lleva 3 pagadas.</li>
 *   <li><b>Comercial Ñandu</b> debe tres meses en UF y no ha hecho nada.</li>
 *   <li><b>Tomas</b> pago en la oficina de Patrimonio: su deuda se retiro.</li>
 *   <li><b>Rodrigo</b> debe cuatro meses; recibio dos codigos y no entro.</li>
 *   <li><b>Carolina</b> pago todo de una vez, con Khipu.</li>
 *   <li><b>Panaderia La Espiga</b> acepto 3 cuotas en UF y pago la primera.</li>
 *   <li><b>Ignacio</b> dejo el departamento en agosto, acepto un convenio y no
 *       pago la primera cuota: es el convenio en riesgo.</li>
 *   <li><b>Daniela</b> acepto 3 cuotas y las pago todas juntas.</li>
 * </ul>
 *
 * <p><b>Instituto Andes</b> (aranceles mensuales) subio su planilla con el
 * corte del 18 de septiembre. DataBridge rechazo a Antonia (debe septiembre:
 * 8 dias). <b>Benjamin</b> debe tres aranceles y <b>Josefina</b> pago los dos
 * suyos con Webpay.
 *
 * <p><b>Clinica Dental Sonrisa Norte</b> (tratamientos de un solo cargo)
 * entrego su cartera por API con el mismo corte. <b>Patricio</b> debe una
 * ortodoncia de julio y <b>Fernanda</b> acepto 6 cuotas por un implante y pago
 * la primera. Las dos son deudas de un solo cargo: con la regla de meses
 * impagos que tenia DataBridge antes se habrian rechazado.
 *
 * <p>Al arrancar se agrega el deudor que falte, sin tocar los que ya estan: una
 * base con datos propios no pierde nada.
 *
 * <p>La agencia, APOFYX, se registra siempre: es la empresa que opera la
 * plataforma, y sin ella no habria a quien emitirle la clave con que entrega
 * cartera. Los acreedores no: cada uno llega con el mandato de su agencia, que
 * lo registra la primera vez. Patrimonio solo se crea aca junto con la
 * historia de la demo, y con {@code app.demo.datos=false} queda fuera todo eso.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final OrganizationRepository organizations;
    private final DebtorRepository debtors;
    private final BatchRepository batches;
    private final DebtRepository debts;
    private final DebtChargeRepository charges;
    private final InstallmentRepository installments;
    private final RepactationRepository repactations;
    private final DebtEventRepository events;
    private final ObjectMapper json;
    private final boolean historia;

    private Organization patrimonio;
    private Organization apofyx;
    private Batch agosto;
    private Batch septiembre;
    private Organization andes;
    private Organization sonrisa;

    public DataSeeder(
            OrganizationRepository organizations,
            DebtorRepository debtors,
            BatchRepository batches,
            DebtRepository debts,
            DebtChargeRepository charges,
            InstallmentRepository installments,
            RepactationRepository repactations,
            DebtEventRepository events,
            ObjectMapper json,
            @Value("${app.demo.datos:true}") boolean historia
    ) {
        this.organizations = organizations;
        this.debtors = debtors;
        this.batches = batches;
        this.debts = debts;
        this.charges = charges;
        this.installments = installments;
        this.repactations = repactations;
        this.events = events;
        this.json = json;
        this.historia = historia;
    }

    @Override
    @Transactional
    public void run(String... args) {
        apofyx = organizacion("77305118-6", "APOFYX SpA", "APOFYX", Organization.Kind.agency);
        if (!historia) {
            return;
        }
        patrimonio = organizacion("76418902-7", "Patrimonio Inmuebles SpA", "Patrimonio Inmuebles",
                Organization.Kind.creditor);
        agosto = lote(patrimonio, "APX-2026-08-19-003", LocalDate.of(2026, 8, 18), cl("2026-08-19T10:12"), 8, 6);
        septiembre = lote(patrimonio, "APX-2026-09-19-004", LocalDate.of(2026, 9, 18), cl("2026-09-19T10:05"), 8, 7);

        felipe();
        nandu();
        tomas();
        rodrigo();
        carolina();
        laEspiga();
        ignacio();
        daniela();

        institutoAndes();
        sonrisaNorte();
    }

    // ------------------------------------------------------------------
    //  Los deudores
    // ------------------------------------------------------------------

    private void felipe() {
        Debt deuda = arriendo("CTR-2025-014", deudor("16482337-7", Debtor.Kind.person, "Felipe Rojas Muñoz",
                        "felipe.rojas@correo.cl", "+56987654321"),
                Debt.Currency.CLP, "Arriendo mensual", "Depto 1204, Av. Irarrázaval 2450, Ñuñoa",
                septiembre, septiembre, List.of(
                        cargo("Arriendo agosto", "2026-08", "520000"),
                        cargo("Arriendo septiembre", "2026-09", "520000")));
        if (deuda == null) {
            return;
        }
        cuota(deuda, 1, "2026-09-05", "1040000", Installment.Status.void_, null, null);
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-09-19T16:20", null, "fe**********@correo.cl");
        evento(deuda, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor, "2026-09-20T11:31", null, null);
        List<Installment> plan = convenio(deuda, "2026-09-20T11:40", 6, "173333", "173335", "2026-10-20");
        pago(deuda, plan, List.of(0), "2026-09-21T20:14", "webpay", "wp-3f8a21", null, null);
        pago(deuda, plan, List.of(1, 2), "2026-09-23T13:02", "mercadopago", "mp-77120934", null, null);
        cerrar(deuda, Debt.Status.repacted, "2026-09-23T13:02");
    }

    private void nandu() {
        Debt deuda = arriendo("CTR-2024-007", deudor("76991245-2", Debtor.Kind.company, "Comercial Ñandú SpA",
                        "administracion@nandu.cl", null),
                Debt.Currency.UF, "Arriendo local comercial", "Local 3, Av. Italia 1320, Providencia",
                agosto, septiembre, List.of(
                        cargo("Arriendo julio", "2026-07", "38.5"),
                        cargo("Arriendo agosto", "2026-08", "38.5"),
                        cargo("Arriendo septiembre", "2026-09", "38.5")));
        if (deuda == null) {
            return;
        }
        cuota(deuda, 1, "2026-08-05", "77.00", Installment.Status.void_, null, null);
        cuota(deuda, 2, "2026-09-05", "115.50", Installment.Status.pending, null, null);
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-08-19T16:05", null, "ad*************@nandu.cl");
        actualizada(deuda, "2026-09-19T10:05", "115.50");
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-09-22T09:40", null, "ad*************@nandu.cl");
        cerrar(deuda, Debt.Status.open, "2026-09-22T09:40");
    }

    private void tomas() {
        Debt deuda = arriendo("CTR-2025-022", deudor("15227640-0", Debtor.Kind.person, "Tomás Fuentes Leiva",
                        "tomas.fuentes@correo.cl", "+56955512340"),
                Debt.Currency.CLP, "Arriendo mensual", "Los Castaños 455, La Florida",
                agosto, septiembre, List.of(
                        cargo("Arriendo julio", "2026-07", "680000"),
                        cargo("Arriendo agosto", "2026-08", "680000")));
        if (deuda == null) {
            return;
        }
        cuota(deuda, 1, "2026-08-05", "1360000", Installment.Status.void_, null, null);
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-08-19T16:10", null, "to***********@correo.cl");
        //  Pago en la oficina el 10 de septiembre, y la cartera siguiente lo retiro.
        evento(deuda, DebtEvent.Type.withdrawn, DebtEvent.Actor.creditor, "2026-09-19T10:05", null, "pago_directo");
        deuda.setWithdrawnReason("pago_directo");
        cerrar(deuda, Debt.Status.withdrawn, "2026-09-19T10:05");
    }

    private void rodrigo() {
        Debt deuda = arriendo("CTR-2025-019", deudor("14583206-3", Debtor.Kind.person, "Rodrigo Pérez Contreras",
                        "rodrigo.perez@correo.cl", "+56961238890"),
                Debt.Currency.CLP, "Arriendo mensual", "Depto 1507, Santa Isabel 470, Santiago",
                agosto, septiembre, List.of(
                        cargo("Arriendo junio", "2026-06", "450000"),
                        cargo("Arriendo julio", "2026-07", "450000"),
                        cargo("Arriendo agosto", "2026-08", "450000"),
                        cargo("Arriendo septiembre", "2026-09", "450000")));
        if (deuda == null) {
            return;
        }
        cuota(deuda, 1, "2026-08-05", "1350000", Installment.Status.void_, null, null);
        cuota(deuda, 2, "2026-09-05", "1800000", Installment.Status.pending, null, null);
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-08-19T16:15", null, "ro************@correo.cl");
        actualizada(deuda, "2026-09-19T10:05", "1800000");
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-09-24T10:00", null, "ro************@correo.cl");
        cerrar(deuda, Debt.Status.open, "2026-09-24T10:00");
    }

    private void carolina() {
        Debt deuda = arriendo("CTR-2026-012", deudor("19230418-0", Debtor.Kind.person, "Carolina Muñoz Vera",
                        "carolina.munoz@correo.cl", "+56978812034"),
                Debt.Currency.CLP, "Arriendo mensual", "Depto 42, Av. Pajaritos 2810, Maipú",
                septiembre, septiembre, List.of(
                        cargo("Arriendo agosto", "2026-08", "380000"),
                        cargo("Arriendo septiembre", "2026-09", "380000")));
        if (deuda == null) {
            return;
        }
        List<Installment> total = List.of(cuota(deuda, 1, "2026-09-05", "760000", Installment.Status.pending, null, null));
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-09-19T16:30", null, "ca*************@correo.cl");
        evento(deuda, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor, "2026-09-21T18:39", null, null);
        pago(deuda, total, List.of(0), "2026-09-21T18:45", "khipu", "kh-4452-0917", null, null);
        evento(deuda, DebtEvent.Type.settled, DebtEvent.Actor.system, "2026-09-21T18:45", null, null);
        cerrar(deuda, Debt.Status.paid, "2026-09-21T18:45");
    }

    private void laEspiga() {
        Debt deuda = arriendo("CTR-2024-019", deudor("76284519-9", Debtor.Kind.company, "Panadería La Espiga Ltda.",
                        "contacto@laespiga.cl", "+56229876543"),
                Debt.Currency.UF, "Arriendo local comercial",
                "Local 12, Gran Avenida José Miguel Carrera 5540, San Miguel",
                agosto, septiembre, List.of(
                        cargo("Arriendo julio", "2026-07", "24"),
                        cargo("Arriendo agosto", "2026-08", "24"),
                        cargo("Arriendo septiembre", "2026-09", "24")));
        if (deuda == null) {
            return;
        }
        cuota(deuda, 1, "2026-08-05", "48.00", Installment.Status.void_, null, null);
        cuota(deuda, 2, "2026-09-05", "72.00", Installment.Status.void_, null, null);
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-08-19T16:20", null, "co**********@laespiga.cl");
        actualizada(deuda, "2026-09-19T10:05", "72.00");
        List<Installment> plan = convenio(deuda, "2026-09-22T10:15", 3, "24.00", "24.00", "2026-10-22");
        //  En UF: se cobro en pesos, con la UF de ese dia.
        pago(deuda, plan, List.of(0), "2026-09-23T12:30", "mercadopago", "mp-77184511", 957037L,
                new BigDecimal("39876.54"));
        cerrar(deuda, Debt.Status.repacted, "2026-09-23T12:30");
    }

    private void ignacio() {
        Debt deuda = arriendo("CTR-2025-027", deudor("17893456-2", Debtor.Kind.person, "Ignacio Tapia Rojas",
                        "ignacio.tapia@correo.cl", "+56987120045"),
                Debt.Currency.CLP, "Arriendo mensual", "Depto 204, Portugal 48, Santiago",
                agosto, agosto, List.of(
                        cargo("Arriendo junio", "2026-06", "350000"),
                        cargo("Arriendo julio", "2026-07", "350000"),
                        cargo("Arriendo agosto", "2026-08", "350000")));
        if (deuda == null) {
            return;
        }
        //  Dejo el departamento el 31 de agosto: su contrato termino, y por eso
        //  no volvio en la cartera de septiembre y el convenio sigue intacto.
        cuota(deuda, 1, "2026-08-05", "1050000", Installment.Status.void_, null, null);
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-08-19T16:25", null, "ig***********@correo.cl");
        evento(deuda, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor, "2026-08-20T17:01", null, null);
        convenio(deuda, "2026-08-20T17:05", 6, "175000", "175000", "2026-09-20");
        cerrar(deuda, Debt.Status.repacted, "2026-08-20T17:05");
    }

    private void daniela() {
        Debt deuda = arriendo("CTR-2026-015", deudor("18642975-3", Debtor.Kind.person, "Daniela Cáceres Flores",
                        "daniela.caceres@correo.cl", "+56954410987"),
                Debt.Currency.CLP, "Arriendo mensual", "Depto 713, Av. Vicuña Mackenna 4860, Macul",
                agosto, septiembre, List.of(
                        cargo("Arriendo julio", "2026-07", "300000"),
                        cargo("Arriendo agosto", "2026-08", "300000"),
                        cargo("Arriendo septiembre", "2026-09", "300000")));
        if (deuda == null) {
            return;
        }
        cuota(deuda, 1, "2026-08-05", "600000", Installment.Status.void_, null, null);
        cuota(deuda, 2, "2026-09-05", "900000", Installment.Status.void_, null, null);
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.agency, "2026-08-19T16:30", null, "da*************@correo.cl");
        actualizada(deuda, "2026-09-19T10:05", "900000");
        List<Installment> plan = convenio(deuda, "2026-09-20T09:10", 3, "300000", "300000", "2026-10-20");
        pago(deuda, plan, List.of(0, 1, 2), "2026-09-24T21:05", "webpay", "wp-8c21d4", null, null);
        evento(deuda, DebtEvent.Type.settled, DebtEvent.Actor.system, "2026-09-24T21:05", null, null);
        cerrar(deuda, Debt.Status.paid, "2026-09-24T21:05");
    }

    // ------------------------------------------------------------------
    //  Instituto Andes: aranceles mensuales, por planilla
    // ------------------------------------------------------------------

    private void institutoAndes() {
        andes = organizacion("77812341-K", "Instituto Profesional Andes Ltda.", "Instituto Andes",
                Organization.Kind.creditor);
        //  Tres en la planilla; Antonia debia solo septiembre (8 dias) y no entro.
        Batch lote = lote(andes, "APX-2026-09-19-005", LocalDate.of(2026, 9, 18), cl("2026-09-19T10:20"), 3, 2);

        Debt benjamin = deuda(andes, "AND-2025-0412", deudor("21345678-4", Debtor.Kind.person, "Benjamín Araya Toro",
                        "benjamin.araya@correo.cl", "+56944120387"),
                Debt.Currency.CLP, "Arancel Técnico en Enfermería",
                referencias("matricula", "AND-2025-0412", "carrera", "Técnico en Enfermería"), lote, List.of(
                        cargo("Arancel julio", "2026-07", "185000", "2026-07-10"),
                        cargo("Arancel agosto", "2026-08", "185000", "2026-08-10"),
                        cargo("Arancel septiembre", "2026-09", "185000", "2026-09-10")));
        if (benjamin != null) {
            cuota(benjamin, 1, "2026-09-10", "555000", Installment.Status.pending, null, null);
            invitacion(benjamin, "2026-09-19T10:20", "be************@correo.cl");
            cerrar(benjamin, Debt.Status.open, "2026-09-19T10:20");
        }

        Debt josefina = deuda(andes, "AND-2024-0931", deudor("20876543-4", Debtor.Kind.person, "Josefina Vidal Cortés",
                        "josefina.vidal@correo.cl", "+56977345120"),
                Debt.Currency.CLP, "Arancel Ingeniería en Informática",
                referencias("matricula", "AND-2024-0931", "carrera", "Ingeniería en Informática"), lote, List.of(
                        cargo("Arancel agosto", "2026-08", "185000", "2026-08-10"),
                        cargo("Arancel septiembre", "2026-09", "185000", "2026-09-10")));
        if (josefina != null) {
            List<Installment> total = List.of(cuota(josefina, 1, "2026-09-10", "370000",
                    Installment.Status.pending, null, null));
            invitacion(josefina, "2026-09-19T10:20", "jo************@correo.cl");
            evento(josefina, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor, "2026-09-22T20:02", null, null);
            pago(josefina, total, List.of(0), "2026-09-22T20:10", "webpay", "wp-91ce07", null, null);
            evento(josefina, DebtEvent.Type.settled, DebtEvent.Actor.system, "2026-09-22T20:10", null, null);
            cerrar(josefina, Debt.Status.paid, "2026-09-22T20:10");
        }
    }

    // ------------------------------------------------------------------
    //  Sonrisa Norte: tratamientos dentales de un solo cargo, por API
    // ------------------------------------------------------------------

    private void sonrisaNorte() {
        sonrisa = organizacion("76998877-7", "Servicios Dentales Sonrisa Norte SpA", "Clínica Dental Sonrisa Norte",
                Organization.Kind.creditor);
        Batch lote = lote(sonrisa, "APX-2026-09-19-006", LocalDate.of(2026, 9, 18), cl("2026-09-19T10:25"), 2, 2);

        Debt patricio = deuda(sonrisa, "SN-2026-118", deudor("13579246-2", Debtor.Kind.person, "Patricio Muñoz Salas",
                        "patricio.munoz@correo.cl", "+56951287734"),
                Debt.Currency.CLP, "Tratamiento de ortodoncia",
                referencias("presupuesto", "SN-2026-118", "tratamiento", "Ortodoncia"), lote, List.of(
                        cargo("Ortodoncia, saldo del presupuesto", null, "890000", "2026-07-05")));
        if (patricio != null) {
            cuota(patricio, 1, "2026-07-05", "890000", Installment.Status.pending, null, null);
            invitacion(patricio, "2026-09-19T10:25", "pa************@correo.cl");
            cerrar(patricio, Debt.Status.open, "2026-09-19T10:25");
        }

        Debt fernanda = deuda(sonrisa, "SN-2026-093", deudor("16789012-1", Debtor.Kind.person, "Fernanda Silva Rojas",
                        "fernanda.silva@correo.cl", "+56962054418"),
                Debt.Currency.CLP, "Implante dental",
                referencias("presupuesto", "SN-2026-093", "tratamiento", "Implante dental"), lote, List.of(
                        cargo("Implante dental", null, "1450000", "2026-06-20")));
        if (fernanda != null) {
            cuota(fernanda, 1, "2026-06-20", "1450000", Installment.Status.void_, null, null);
            invitacion(fernanda, "2026-09-19T10:25", "fe************@correo.cl");
            evento(fernanda, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor, "2026-09-21T10:52", null, null);
            List<Installment> plan = convenio(fernanda, "2026-09-21T11:00", 6, "241666", "241670", "2026-10-21");
            pago(fernanda, plan, List.of(0), "2026-09-21T11:05", "khipu", "kh-5108-0921", null, null);
            cerrar(fernanda, Debt.Status.repacted, "2026-09-21T11:05");
        }
    }

    // ------------------------------------------------------------------
    //  Como se arma cada pieza
    // ------------------------------------------------------------------

    /** Un cargo, con su periodo si es un cobro mensual. Los arriendos de Patrimonio vencen el 5. */
    private record Cargo(String concepto, String periodo, BigDecimal monto, LocalDate vence) {}

    private static Cargo cargo(String concepto, String periodo, String monto) {
        return cargo(concepto, periodo, monto, periodo + "-05");
    }

    private static Cargo cargo(String concepto, String periodo, String monto, String vence) {
        return new Cargo(concepto, periodo, new BigDecimal(monto), LocalDate.parse(vence));
    }

    private static Map<String, String> referencias(String... claveValor) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (int i = 0; i < claveValor.length; i += 2) {
            refs.put(claveValor[i], claveValor[i + 1]);
        }
        return refs;
    }

    /** La invitacion que sale sola cuando la deuda entra (InvitacionService). */
    private void invitacion(Debt deuda, String cuando, String destino) {
        evento(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.system, cuando, null, destino);
    }

    private static Instant cl(String fechaYHora) {
        return LocalDateTime.parse(fechaYHora).atZone(CHILE).toInstant();
    }

    private Organization organizacion(String rut, String razon, String fantasia, Organization.Kind tipo) {
        return organizations.findByRut(rut).orElseGet(() -> {
            Organization organizacion = new Organization();
            organizacion.setRut(rut);
            organizacion.setLegalName(razon);
            organizacion.setTradeName(fantasia);
            organizacion.setKind(tipo);
            return organizations.save(organizacion);
        });
    }

    private Batch lote(Organization acreedor, String idExterno, LocalDate corte, Instant recibido, int recibidas,
                       int aceptadas) {
        return batches.findBySenderAndExternalId(apofyx, idExterno).orElseGet(() -> {
            Batch lote = new Batch();
            lote.setSender(apofyx);
            lote.setCreditor(acreedor);
            lote.setExternalId(idExterno);
            lote.setCutOff(corte);
            lote.setPayloadHash("0".repeat(64));
            lote.setReceivedCount(recibidas);
            lote.setAcceptedCount(aceptadas);
            lote.setRejectedCount(recibidas - aceptadas);
            lote.setReceivedAt(recibido);
            return batches.save(lote);
        });
    }

    private Debtor deudor(String rut, Debtor.Kind tipo, String nombre, String correo, String telefono) {
        return debtors.findByRut(rut).orElseGet(() -> {
            Debtor deudor = new Debtor();
            deudor.setRut(rut);
            deudor.setKind(tipo);
            deudor.setFullName(nombre);
            deudor.setEmail(correo);
            deudor.setPhone(telefono);
            return debtors.save(deudor);
        });
    }

    /** Un arriendo de Patrimonio: el contrato y la propiedad son sus referencias. */
    private Debt arriendo(String contrato, Debtor deudor, Debt.Currency moneda, String concepto, String propiedad,
                          Batch primero, Batch ultimo, List<Cargo> cargos) {
        return deuda(patrimonio, contrato, deudor, moneda, concepto,
                referencias("contrato", contrato, "propiedad", propiedad), primero, ultimo, cargos);
    }

    private Debt deuda(Organization acreedor, String idExterno, Debtor deudor, Debt.Currency moneda, String concepto,
                       Map<String, String> refs, Batch lote, List<Cargo> cargos) {
        return deuda(acreedor, idExterno, deudor, moneda, concepto, refs, lote, lote, cargos);
    }

    /**
     * La deuda con sus cargos, tal como llego en su primera cartera. Devuelve
     * null si ya existe: esa se deja como esta.
     */
    private Debt deuda(Organization acreedor, String idExterno, Debtor deudor, Debt.Currency moneda, String concepto,
                       Map<String, String> refs, Batch primero, Batch ultimo, List<Cargo> cargos) {
        if (debts.findByCreditorAndExternalId(acreedor, idExterno).isPresent()) {
            return null;
        }
        Debt deuda = new Debt();
        deuda.setCreditor(acreedor);
        deuda.setDebtor(deudor);
        deuda.setExternalId(idExterno);
        deuda.setCurrency(moneda);
        deuda.setConcept(concepto);
        deuda.setRefs(comoJson(refs));
        deuda.setOriginalAmount(cargos.stream().map(Cargo::monto).reduce(BigDecimal.ZERO, BigDecimal::add));
        deuda.setFirstBatch(primero);
        deuda.setLastBatch(ultimo);
        Instant recibida = primero.getReceivedAt();
        deuda.setCreatedAt(recibida);
        deuda.setUpdatedAt(recibida);
        debts.save(deuda);

        for (Cargo c : cargos) {
            DebtCharge fila = new DebtCharge();
            fila.setDebt(deuda);
            fila.setConcept(c.concepto());
            fila.setPeriod(c.periodo());
            fila.setAmount(c.monto());
            fila.setDueDate(c.vence());
            charges.save(fila);
        }
        //  Lo que registro la primera cartera: en agosto, los cargos hasta agosto.
        BigDecimal primeraVez = cargos.stream()
                .filter(c -> primero != agosto || c.periodo().compareTo("2026-08") <= 0)
                .map(Cargo::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        evento(deuda, DebtEvent.Type.registered, DebtEvent.Actor.agency, recibida, primeraVez, primero.getExternalId());
        return deuda;
    }

    private Installment cuota(Debt deuda, int numero, String vence, String monto, Installment.Status estado,
                              Instant pagada, Repactation convenio) {
        Installment cuota = new Installment();
        cuota.setDebt(deuda);
        cuota.setRepactation(convenio);
        cuota.setNumber((short) numero);
        cuota.setDueDate(LocalDate.parse(vence));
        cuota.setAmount(new BigDecimal(monto));
        cuota.setStatus(estado);
        cuota.setPaidAt(pagada);
        return installments.save(cuota);
    }

    /**
     * El convenio: sus cuotas mensuales desde {@code primera}, la ultima con el
     * redondeo, como las arma RepactationService.
     */
    private List<Installment> convenio(Debt deuda, String aceptado, int meses, String mensual, String ultima,
                                       String primera) {
        Repactation convenio = new Repactation();
        convenio.setDebt(deuda);
        convenio.setMonths((short) meses);
        convenio.setMonthlyAmount(new BigDecimal(mensual));
        convenio.setCurrency(deuda.getCurrency());
        convenio.setAcceptedAt(cl(aceptado));
        repactations.save(convenio);

        int numero = installments.findByDebtOrderByNumberAsc(deuda).size() + 1;
        List<Installment> plan = new ArrayList<>();
        LocalDate vence = LocalDate.parse(primera);
        for (int i = 0; i < meses; i++) {
            plan.add(cuota(deuda, numero + i, vence.plusMonths(i).toString(), i == meses - 1 ? ultima : mensual,
                    Installment.Status.pending, null, convenio));
        }
        evento(deuda, DebtEvent.Type.repacted, DebtEvent.Actor.debtor, cl(aceptado), new BigDecimal(mensual),
                meses + " cuotas");
        return plan;
    }

    /** Un pago de algunas cuotas del plan (por su lugar, desde 0), con su detalle como lo guarda un pago real. */
    private void pago(Debt deuda, List<Installment> plan, List<Integer> lugares, String cuando, String pasarela,
                      String transaccion, Long montoClp, BigDecimal valorUf) {
        Instant pagado = cl(cuando);
        BigDecimal monto = BigDecimal.ZERO;
        for (int lugar : lugares) {
            Installment cuota = plan.get(lugar);
            cuota.setStatus(Installment.Status.paid);
            cuota.setPaidAt(pagado);
            installments.save(cuota);
            monto = monto.add(cuota.getAmount());
        }
        Long pesos = montoClp != null ? montoClp : monto.longValueExact();
        DetallePago detalle = new DetallePago(null, pesos, valorUf, pasarela,
                lugares.stream().map(l -> l + 1).toList(), plan.size(), null);
        DebtEvent aplicado = DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                .conMonto(monto, deuda.getCurrency())
                .conReferencia(pasarela + ":" + transaccion)
                .conDetalle(comoJson(detalle));
        aplicado.setOccurredAt(pagado);
        events.save(aplicado);
    }

    private void actualizada(Debt deuda, String cuando, String total) {
        evento(deuda, DebtEvent.Type.updated, DebtEvent.Actor.agency, cl(cuando), new BigDecimal(total),
                septiembre.getExternalId());
    }

    private void evento(Debt deuda, DebtEvent.Type tipo, DebtEvent.Actor quien, String cuando, BigDecimal monto,
                        String referencia) {
        evento(deuda, tipo, quien, cl(cuando), monto, referencia);
    }

    private void evento(Debt deuda, DebtEvent.Type tipo, DebtEvent.Actor quien, Instant cuando, BigDecimal monto,
                        String referencia) {
        DebtEvent evento = DebtEvent.de(deuda, tipo, quien).conReferencia(referencia);
        if (monto != null) {
            evento.conMonto(monto, deuda.getCurrency());
        }
        evento.setOccurredAt(cuando);
        events.save(evento);
    }

    private void cerrar(Debt deuda, Debt.Status estado, String ultimoCambio) {
        deuda.setStatus(estado);
        deuda.setUpdatedAt(cl(ultimoCambio));
        debts.save(deuda);
    }

    private String comoJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo escribir en JSON un dato de la demo", e);
        }
    }
}
