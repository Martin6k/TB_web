package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.util.Hash;
import com.tbridge.common.util.Rut;
import com.tbridge.debt.dto.evento.DeudaRetiradaDatos;
import com.tbridge.debt.dto.evento.LoteProcesadoDatos;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.CarteraResponse.ErrorDeuda;
import com.tbridge.debt.dto.response.CarteraResponse.ResultadoDeuda;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtCharge;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.BatchRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Recepcion de una Cartera v1.
 *
 * <p>Implementa docs/integracion/README.md §6. Es el mismo contrato con que
 * APOFYX recibe la cartera de sus clientes: un solo formato para toda la
 * cadena, de modo que el acreedor que quiera trabajar sin agencia usa el mismo
 * adaptador contra DataBridge.
 *
 * <p>Dos principios explican casi todo el archivo:
 *
 * <ol>
 *   <li><b>Aceptacion parcial.</b> Una deuda mal formada se rechaza sola, con
 *       su motivo, y las demas entran igual. Un RUT mal escrito no puede
 *       bloquear las otras 4.999.</li>
 *   <li><b>Idempotencia.</b> El mismo lote enviado dos veces devuelve la misma
 *       respuesta sin volver a procesar nada. El mismo id con otro contenido
 *       se rechaza: un numero de lote no se reutiliza.</li>
 * </ol>
 *
 * <p><b>Solo deudores morosos.</b> Es el alcance de DataBridge: una deuda entra
 * a cobranza cuando tiene al menos {@code app.cartera.min-meses-impagos}
 * meses impagos (dos, por omision). Con menos todavia no es mora, y se rechaza
 * sola con el codigo {@code bajo_umbral_mora}.
 *
 * <p><b>Lo que se pago por DataBridge manda.</b> Una deuda en convenio que
 * vuelve en la cartera del mes siguiente conserva su convenio, y una pagada
 * vuelve a cobranza solo con cargos posteriores a los que se pagaron.
 */
@Service
public class CarteraIntakeService {

    private static final Set<String> MOTIVOS_DE_RETIRO = Set.of(
            "pago_directo", "acuerdo_directo", "error", "disputa_resuelta", "fuera_de_mandato", "otro");

    private final OrganizationRepository organizations;
    private final MandateRepository mandates;
    private final CampaignRepository campaigns;
    private final BatchRepository batches;
    private final DebtorRepository debtors;
    private final DebtRepository debts;
    private final DebtChargeRepository charges;
    private final InstallmentRepository installments;
    private final DebtEventRepository events;
    private final ObjectMapper json;
    private final EventosService eventos;
    private final int minMesesImpagos;

    public CarteraIntakeService(
            OrganizationRepository organizations,
            MandateRepository mandates,
            CampaignRepository campaigns,
            BatchRepository batches,
            DebtorRepository debtors,
            DebtRepository debts,
            DebtChargeRepository charges,
            InstallmentRepository installments,
            DebtEventRepository events,
            ObjectMapper json,
            EventosService eventos,
            @Value("${app.cartera.min-meses-impagos:2}") int minMesesImpagos
    ) {
        if (minMesesImpagos < 1) {
            throw new IllegalStateException("app.cartera.min-meses-impagos tiene que ser al menos 1");
        }
        this.organizations = organizations;
        this.mandates = mandates;
        this.campaigns = campaigns;
        this.batches = batches;
        this.debtors = debtors;
        this.debts = debts;
        this.charges = charges;
        this.installments = installments;
        this.events = events;
        this.json = json;
        this.eventos = eventos;
        this.minMesesImpagos = minMesesImpagos;
    }

    /** Desde cuantos meses impagos entra una deuda: el alcance de DataBridge. */
    public int minMesesImpagos() {
        return minMesesImpagos;
    }

    // ------------------------------------------------------------------
    //  Entrada
    // ------------------------------------------------------------------

    @Transactional
    public CarteraResponse recibir(Organization emisor, JsonNode payload, Batch.Source origen) {
        if (payload == null || !payload.isObject()) {
            throw new CarteraInvalida("cuerpo_invalido", "El cuerpo tiene que ser un objeto JSON");
        }
        if (!"1.0".equals(texto(payload.get("version")))) {
            throw new CarteraInvalida("version_no_soportada", "Esta version del contrato es la 1.0");
        }

        JsonNode lote = payload.get("lote");
        String idExterno = lote == null ? null : texto(lote.get("id_externo"));
        LocalDate corte = lote == null ? null : fecha(lote.get("fecha_corte"));
        if (idExterno == null || idExterno.isBlank() || corte == null) {
            throw new CarteraInvalida("lote_incompleto", "El lote necesita id_externo y fecha_corte");
        }

        Organization acreedor = resolverAcreedor(lote, emisor);
        Mandate mandato = resolverMandato(emisor, acreedor, corte);
        Campaign campana = resolverCampana(lote, emisor, acreedor);

        JsonNode deudas = payload.get("deudas");
        if (deudas == null || !deudas.isArray() || deudas.isEmpty()) {
            throw new CarteraInvalida("sin_deudas", "El lote no trae deudas");
        }

        //  Idempotencia
        String firma = huella(payload);
        Batch anterior = batches.findBySenderAndExternalId(emisor, idExterno).orElse(null);
        if (anterior != null) {
            if (anterior.getPayloadHash().equals(firma)) {
                return leerRespuesta(anterior);
            }
            throw new CarteraInvalida("lote_id_reutilizado",
                    "El lote " + idExterno + " ya se recibio con otro contenido", 409);
        }

        Batch batch = new Batch();
        batch.setSender(emisor);
        batch.setCreditor(acreedor);
        batch.setExternalId(idExterno);
        batch.setCutOff(corte);
        batch.setSource(origen);
        batch.setPayloadHash(firma);
        batch.setReceivedCount(deudas.size());
        batches.save(batch);

        List<ResultadoDeuda> resultados = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();
        int aceptadas = 0;

        for (JsonNode deuda : deudas) {
            ResultadoDeuda resultado = procesar(deuda, acreedor, batch, campana, corte, mandato, vistos);
            if (resultado.aceptada()) {
                aceptadas++;
            }
            resultados.add(resultado);
        }

        batch.setAcceptedCount(aceptadas);
        batch.setRejectedCount(deudas.size() - aceptadas);
        batch.setStatus(aceptadas > 0 ? Batch.Status.processed : Batch.Status.rejected);

        CarteraResponse respuesta = new CarteraResponse(idExterno, false, deudas.size(), aceptadas,
                batch.getRejectedCount(), resultados, camposIgnorados(payload));
        eventos.publicarDeLote(batch, EventosService.LOTE_PROCESADO,
                datosDelLote(batch, corte, resultados), Instant.now());

        batch.setResponse(comoJson(respuesta));
        batches.save(batch);
        return respuesta;
    }

    // ------------------------------------------------------------------
    //  Quien entrega y con que autorizacion
    // ------------------------------------------------------------------

    private Organization resolverAcreedor(JsonNode lote, Organization emisor) {
        JsonNode nodo = lote.get("acreedor");
        String rut = Rut.normalizar(nodo == null ? null : texto(nodo.get("rut")));
        if (!Rut.esValido(rut)) {
            throw new CarteraInvalida("acreedor_invalido", "El RUT del acreedor no es valido");
        }
        return organizations.findByRut(rut).orElseThrow(() -> new CarteraInvalida(
                "acreedor_desconocido", "El acreedor " + rut + " no esta registrado", 404));
    }

    /**
     * El mandato que autoriza a la agencia.
     *
     * <p>Cuando el emisor es el propio acreedor no hace falta ninguno: nadie
     * necesita permiso para entregar lo suyo.
     */
    private Mandate resolverMandato(Organization emisor, Organization acreedor, LocalDate corte) {
        if (emisor.getId().equals(acreedor.getId())) {
            return null;
        }
        return mandates
                .findByAgencyAndCreditorAndStatus(emisor, acreedor, Mandate.Status.active).stream()
                .filter(m -> m.vigenteAl(corte))
                .findFirst()
                .orElseThrow(() -> new CarteraInvalida(
                        "sin_mandato",
                        "No hay mandato vigente de " + emisor.getRut() + " sobre " + acreedor.getRut(),
                        403));
    }

    private Campaign resolverCampana(JsonNode lote, Organization emisor, Organization acreedor) {
        JsonNode mandato = lote.get("mandato");
        if (mandato == null || mandato.isNull()) {
            return null;
        }
        String idCampana = texto(mandato.get("campana_id_externo"));
        if (idCampana == null || idCampana.isBlank()) {
            return null;
        }
        Campaign campana = campaigns.findByAgencyAndExternalId(emisor, idCampana)
                .orElseThrow(() -> new CarteraInvalida("campana_desconocida",
                        "La campana " + idCampana + " no esta registrada", 404));
        if (!campana.getCreditor().getId().equals(acreedor.getId())) {
            throw new CarteraInvalida("campana_desconocida",
                    "Esa campana no es de ese acreedor", 404);
        }
        return campana;
    }

    // ------------------------------------------------------------------
    //  Una deuda
    // ------------------------------------------------------------------

    private ResultadoDeuda procesar(JsonNode deuda, Organization acreedor, Batch batch,
                                         Campaign campana, LocalDate corte, Mandate mandato,
                                         Set<String> vistos) {
        String idDeuda = texto(deuda.get("id_externo"));
        if (idDeuda == null || idDeuda.isBlank()) {
            return rechazo(null, error("id_externo", "id_externo_faltante",
                    "La deuda no trae id_externo"));
        }
        if (!vistos.add(idDeuda)) {
            return rechazo(idDeuda, error("id_externo", "id_duplicado_en_lote",
                    idDeuda + " viene dos veces en el mismo lote"));
        }

        String accion = deuda.has("accion") ? texto(deuda.get("accion")) : "registrar";
        Debt existente = debts.findByCreditorAndExternalId(acreedor, idDeuda).orElse(null);

        //  Una deuda pagada solo vuelve registrandola de nuevo, con cargos nuevos:
        //  eso se revisa al leerlos. Retirarla no cambia nada.
        if (existente != null && existente.getStatus() == Debt.Status.paid && !"registrar".equals(accion)) {
            return rechazo(idDeuda, error("id_externo", "deuda_saldada",
                    "Esa deuda ya se pago y no se puede modificar"));
        }

        if ("retirar".equals(accion)) {
            return retirar(deuda, idDeuda, existente, batch);
        }
        if (!"registrar".equals(accion)) {
            return rechazo(idDeuda, error("accion", "accion_invalida",
                    "La accion va como registrar o retirar"));
        }
        return registrar(deuda, idDeuda, acreedor, batch, campana, corte, mandato, existente);
    }

    /**
     * Como quedo el lote, para quien lo entrego: cuantas entraron y como se
     * reparte la mora. El promedio va solo sobre las deudas en pesos; una
     * media que mezclara pesos con UF no significaria nada.
     */
    private LoteProcesadoDatos datosDelLote(Batch batch, LocalDate corte, List<ResultadoDeuda> resultados) {
        Map<String, String> tramoDe = new LinkedHashMap<>();
        for (ResultadoDeuda fila : resultados) {
            if (fila.tramo() != null) {
                tramoDe.put(fila.idExterno(), fila.tramo());
            }
        }
        Map<String, int[]> cuantas = new LinkedHashMap<>();          // tramo -> [deudas, enPesos]
        Map<String, BigDecimal> sumaClp = new LinkedHashMap<>();
        for (Debt deuda : debts.findByLastBatch(batch)) {
            String tramo = tramoDe.get(deuda.getExternalId());
            if (tramo == null) {
                continue;
            }
            int[] conteo = cuantas.computeIfAbsent(tramo, k -> new int[2]);
            conteo[0]++;
            if (deuda.getCurrency() == Debt.Currency.CLP) {
                conteo[1]++;
                sumaClp.merge(tramo, deuda.getOriginalAmount(), BigDecimal::add);
            }
        }
        List<LoteProcesadoDatos.Tramo> tramos = new ArrayList<>();
        cuantas.forEach((tramo, conteo) -> tramos.add(new LoteProcesadoDatos.Tramo(tramo, conteo[0],
                conteo[1] == 0 ? null : sumaClp.get(tramo)
                        .divide(BigDecimal.valueOf(conteo[1]), 0, RoundingMode.HALF_UP).longValue())));

        return new LoteProcesadoDatos(corte.toString().substring(0, 7), corte.toString(),
                batch.getReceivedCount(), batch.getAcceptedCount(), batch.getRejectedCount(), tramos);
    }

    private ResultadoDeuda retirar(JsonNode deuda, String idDeuda, Debt existente, Batch batch) {
        String motivo = texto(deuda.get("motivo_retiro"));
        if (motivo == null || !MOTIVOS_DE_RETIRO.contains(motivo)) {
            return rechazo(idDeuda, error("motivo_retiro", "motivo_invalido",
                    "Un retiro necesita un motivo valido"));
        }
        if (existente == null) {
            return rechazo(idDeuda, error("id_externo", "deuda_no_encontrada",
                    "No hay ninguna deuda con ese id para retirar"));
        }
        existente.setStatus(Debt.Status.withdrawn);
        existente.setWithdrawnReason(motivo);
        existente.setLastBatch(batch);
        existente.setUpdatedAt(Instant.now());
        debts.save(existente);

        //  Retirar no borra: las cuotas pendientes se anulan para que nadie
        //  siga cobrandolas, y queda el rastro de que existieron.
        for (Installment cuota : installments.findByDebtAndStatus(existente, Installment.Status.pending)) {
            cuota.setStatus(Installment.Status.void_);
            installments.save(cuota);
        }
        events.save(DebtEvent.de(existente, DebtEvent.Type.withdrawn, DebtEvent.Actor.creditor)
                .conReferencia(motivo));
        eventos.publicar(existente, EventosService.DEUDA_RETIRADA,
                new DeudaRetiradaDatos(existente.getExternalId(), motivo), Instant.now());
        return ResultadoDeuda.retirada(idDeuda);
    }

    private ResultadoDeuda registrar(JsonNode deuda, String idDeuda, Organization acreedor,
                                          Batch batch, Campaign campana, LocalDate corte,
                                          Mandate mandato, Debt existente) {
        List<ErrorDeuda> errores = new ArrayList<>();

        JsonNode nodoDeudor = deuda.get("deudor");
        String rut = Rut.normalizar(nodoDeudor == null ? null : texto(nodoDeudor.get("rut")));
        if (!Rut.esValido(rut)) {
            errores.add(error("deudor.rut", "rut_invalido",
                    "El RUT no cumple el formato o el digito verificador no corresponde"));
        }
        String correo = nodoDeudor == null ? null : texto(nodoDeudor.get("correo"));
        String telefono = nodoDeudor == null ? null : texto(nodoDeudor.get("telefono"));
        if (vacio(correo) && vacio(telefono)) {
            errores.add(error("deudor", "sin_canal_contacto",
                    "El deudor no trae ni correo ni telefono"));
        }
        String tipo = nodoDeudor == null ? null : texto(nodoDeudor.get("tipo"));
        if (!"persona".equals(tipo) && !"empresa".equals(tipo)) {
            errores.add(error("deudor.tipo", "tipo_invalido", "El tipo va como persona o empresa"));
        }
        String nombre = nodoDeudor == null ? null : texto(nodoDeudor.get("nombre"));
        if (vacio(nombre)) {
            errores.add(error("deudor.nombre", "nombre_faltante", "El deudor no trae nombre"));
        }

        String moneda = texto(deuda.get("moneda"));
        if (!"CLP".equals(moneda) && !"UF".equals(moneda)) {
            errores.add(error("moneda", "moneda_invalida", "La moneda va como CLP o UF"));
        }
        String concepto = texto(deuda.get("concepto"));
        if (vacio(concepto)) {
            errores.add(error("concepto", "concepto_faltante", "La deuda no trae concepto"));
        }

        List<CargoLeido> cargos = new ArrayList<>();
        JsonNode nodoCargos = deuda.get("cargos");
        if (nodoCargos == null || !nodoCargos.isArray() || nodoCargos.isEmpty()) {
            errores.add(error("cargos", "sin_cargos", "La deuda no trae cargos"));
        } else {
            int i = 0;
            for (JsonNode nodo : nodoCargos) {
                leerCargo(nodo, i++, moneda, corte, cargos, errores);
            }
        }

        long mora = cargos.stream()
                .map(CargoLeido::vence)
                .min(LocalDate::compareTo)
                .map(masAntiguo -> ChronoUnit.DAYS.between(masAntiguo, corte))
                .orElse(0L);
        short maximo = mandato == null ? Short.MAX_VALUE : mandato.getMaxOverdueDays();
        if (mora > maximo) {
            errores.add(error("cargos", "mora_fuera_de_mandato",
                    mora + " dias de mora: pasados los " + maximo + " el caso vuelve al acreedor"));
        }

        boolean todosLeidos = nodoCargos != null && nodoCargos.isArray() && cargos.size() == nodoCargos.size();

        //  Una deuda pagada vuelve a cobranza solo con cargos posteriores a los
        //  que se pagaron: el arrendatario se volvio a atrasar. Con alguno de los
        //  ya pagados seria cobrarle dos veces lo mismo.
        boolean pagada = existente != null && existente.getStatus() == Debt.Status.paid;
        if (pagada && todosLeidos && !soloCargosNuevos(existente, cargos)) {
            errores.add(error("id_externo", "deuda_saldada",
                    "Esa deuda ya se pago: vuelve a cobranza solo con cargos posteriores a los que se pagaron"));
        }

        //  El alcance: deudores morosos. Se exige solo al ENTRAR, y una deuda
        //  pagada que vuelve entra de nuevo. Una que ya esta en gestion puede
        //  volver con menos cargos (el arrendatario pago una parte en la
        //  oficina), y rechazarla dejaria a DataBridge cobrando un monto que ya
        //  no existe. Y solo si se leyeron todos los cargos: si alguno venia
        //  malo, contar los buenos no dice nada.
        long meses = mesesImpagos(cargos);
        if ((existente == null || pagada) && todosLeidos && meses < minMesesImpagos) {
            errores.add(error("cargos", "bajo_umbral_mora", "Tiene " + meses
                    + (meses == 1 ? " mes impago" : " meses impagos")
                    + ": DataBridge recibe deudas desde " + minMesesImpagos + " meses impagos"));
        }

        if (!errores.isEmpty()) {
            return rechazo(idDeuda, errores);
        }

        Debtor deudor = guardarDeudor(rut, tipo, nombre, correo, telefono);
        BigDecimal total = cargos.stream()
                .map(CargoLeido::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean nueva = existente == null;
        Debt registro = nueva ? new Debt() : existente;
        boolean sinCambios = !nueva && mismosCargos(registro, cargos)
                && (registro.getStatus() == Debt.Status.open || registro.getStatus() == Debt.Status.repacted)
                && registro.getDebtor().getId().equals(deudor.getId());

        registro.setCreditor(acreedor);
        registro.setDebtor(deudor);
        registro.setExternalId(idDeuda);
        registro.setCurrency(Debt.Currency.valueOf(moneda));
        registro.setConcept(concepto);
        registro.setRefs(deuda.has("referencias") ? deuda.get("referencias").toString() : null);
        registro.setOriginalAmount(total);
        registro.setLastBatch(batch);
        registro.setMandate(mandato);
        if (campana != null) {
            //  Una cartera sin campana no borra la que la deuda ya tenia.
            registro.setCampaign(campana);
        }
        registro.setUpdatedAt(Instant.now());
        if (nueva) {
            registro.setFirstBatch(batch);
        }
        if (registro.getStatus() == Debt.Status.withdrawn || registro.getStatus() == Debt.Status.paid) {
            //  El acreedor la devuelve a gestion, o el deudor se volvio a
            //  atrasar: vuelve a estar abierta.
            registro.setWithdrawnReason(null);
            registro.setStatus(Debt.Status.open);
        }
        debts.save(registro);

        if (!sinCambios) {
            reemplazarCargos(registro, cargos, total);
        }

        events.save(DebtEvent.de(registro,
                        nueva ? DebtEvent.Type.registered : DebtEvent.Type.updated,
                        DebtEvent.Actor.creditor)
                .conMonto(total, registro.getCurrency())
                .conReferencia(batch.getExternalId()));

        return ResultadoDeuda.registrada(idDeuda,
                nueva ? "registrada" : (sinCambios ? "sin_cambios" : "actualizada"), mora, tramo(mora));
    }

    private void leerCargo(JsonNode nodo, int indice, String moneda, LocalDate corte,
                           List<CargoLeido> cargos, List<ErrorDeuda> errores) {
        BigDecimal monto = monto(nodo.get("monto"), moneda);
        if (monto == null) {
            errores.add(error("cargos[" + indice + "].monto", "monto_invalido",
                    "Monto menor o igual a cero, con decimales en pesos, "
                            + "o con mas de dos decimales en UF"));
            return;
        }
        LocalDate vence = fecha(nodo.get("fecha_vencimiento"));
        if (vence == null) {
            errores.add(error("cargos[" + indice + "].fecha_vencimiento", "fecha_invalida",
                    "La fecha va como 2026-09-05"));
            return;
        }
        if (!vence.isBefore(corte)) {
            errores.add(error("cargos[" + indice + "].fecha_vencimiento", "cargo_no_vencido",
                    "Vence el " + vence + " y el corte es el " + corte + ": todavia no es mora"));
            return;
        }
        String concepto = texto(nodo.get("concepto"));
        if (vacio(concepto)) {
            errores.add(error("cargos[" + indice + "].concepto", "concepto_faltante",
                    "El cargo no trae concepto"));
            return;
        }
        cargos.add(new CargoLeido(concepto, texto(nodo.get("periodo")), monto, vence));
    }

    private Debtor guardarDeudor(String rut, String tipo, String nombre, String correo, String telefono) {
        Debtor deudor = debtors.findByRut(rut).orElseGet(Debtor::new);
        deudor.setRut(rut);
        deudor.setKind("empresa".equals(tipo) ? Debtor.Kind.company : Debtor.Kind.person);
        deudor.setFullName(nombre);
        deudor.setEmail(vacio(correo) ? null : correo);
        deudor.setPhone(vacio(telefono) ? null : telefono);
        deudor.setUpdatedAt(Instant.now());
        return debtors.save(deudor);
    }

    /**
     * Los cargos se reemplazan, no se acumulan.
     *
     * <p>El acreedor manda lo que se debe HOY. Si le pagaron una parte en la
     * oficina, reenvia la deuda con el saldo menor, y conservar los cargos
     * viejos dejaria a DataBridge cobrando un monto que ya no existe.
     *
     * <p>Las cuotas pendientes tambien se rehacen, salvo las de un convenio: el
     * deudor lo acepto y lo esta pagando, y que el acreedor vuelva a mandar la
     * deuda en su cartera del mes no es una decision sobre eso (ver
     * {@link #conservarConvenio}). Las pagadas no se tocan: eso ya ocurrio.
     */
    private void reemplazarCargos(Debt deuda, List<CargoLeido> cargos, BigDecimal total) {
        charges.deleteByDebt(deuda);
        for (CargoLeido cargo : cargos) {
            DebtCharge fila = new DebtCharge();
            fila.setDebt(deuda);
            fila.setConcept(cargo.concepto());
            fila.setPeriod(cargo.periodo());
            fila.setAmount(cargo.monto());
            fila.setDueDate(cargo.vence());
            charges.save(fila);
        }

        LocalDate ultimoCargo = cargos.stream().map(CargoLeido::vence).max(LocalDate::compareTo).orElseThrow();
        List<Installment> pendientes = installments.findByDebtAndStatus(deuda, Installment.Status.pending);
        if (deuda.getStatus() == Debt.Status.repacted && pendientes.stream().anyMatch(Installment::enConvenio)) {
            conservarConvenio(deuda, pendientes, ultimoCargo, total);
            return;
        }
        for (Installment pendiente : pendientes) {
            pendiente.setStatus(Installment.Status.void_);
            installments.save(pendiente);
        }
        nuevaCuota(deuda, ultimoCargo, total);
    }

    /**
     * El convenio sigue en pie. Lo que el acreedor informa de mas —un mes
     * nuevo— queda como una cuota aparte, fuera del convenio; lo que informa
     * de menos —le pagaron una parte en la oficina— se descuenta de las
     * ultimas cuotas, que es donde el plan termina antes. Una cuota aparte de
     * una cartera anterior se rehace: el acreedor manda lo que se debe hoy.
     */
    private void conservarConvenio(Debt deuda, List<Installment> pendientes, LocalDate ultimoCargo,
                                   BigDecimal total) {
        List<Installment> delConvenio = new ArrayList<>();
        for (Installment cuota : pendientes) {
            if (cuota.enConvenio()) {
                delConvenio.add(cuota);
            } else {
                cuota.setStatus(Installment.Status.void_);
                installments.save(cuota);
            }
        }
        delConvenio.sort(DebtService.EN_ORDEN);
        BigDecimal diferencia = total.subtract(delConvenio.stream()
                .map(Installment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (diferencia.signum() > 0) {
            nuevaCuota(deuda, ultimoCargo, diferencia);
            return;
        }
        BigDecimal porDescontar = diferencia.negate();
        for (Installment cuota : delConvenio.reversed()) {
            if (porDescontar.signum() == 0) {
                break;
            }
            if (cuota.getAmount().compareTo(porDescontar) <= 0) {
                porDescontar = porDescontar.subtract(cuota.getAmount());
                cuota.setStatus(Installment.Status.void_);
            } else {
                cuota.setAmount(cuota.getAmount().subtract(porDescontar));
                porDescontar = BigDecimal.ZERO;
            }
            installments.save(cuota);
        }
    }

    private void nuevaCuota(Debt deuda, LocalDate vence, BigDecimal monto) {
        short numero = (short) (installments.findByDebtOrderByNumberAsc(deuda).stream()
                .mapToInt(Installment::getNumber).max().orElse(0) + 1);
        Installment cuota = new Installment();
        cuota.setDebt(deuda);
        cuota.setNumber(numero);
        cuota.setDueDate(vence);
        cuota.setAmount(monto);
        installments.save(cuota);
    }

    /** Si todos los cargos vencen despues del ultimo que tenia la deuda cuando se pago. */
    private boolean soloCargosNuevos(Debt pagada, List<CargoLeido> cargos) {
        LocalDate ultimoPagado = charges.findByDebtOrderByDueDateAsc(pagada).stream()
                .map(DebtCharge::getDueDate).max(LocalDate::compareTo).orElse(null);
        return ultimoPagado == null || cargos.stream().allMatch(c -> c.vence().isAfter(ultimoPagado));
    }

    private boolean mismosCargos(Debt deuda, List<CargoLeido> nuevos) {
        List<DebtCharge> actuales = charges.findByDebtOrderByDueDateAsc(deuda);
        if (actuales.size() != nuevos.size()) {
            return false;
        }
        List<CargoLeido> ordenados = nuevos.stream()
                .sorted((a, b) -> a.vence().compareTo(b.vence())).toList();
        for (int i = 0; i < actuales.size(); i++) {
            DebtCharge actual = actuales.get(i);
            CargoLeido nuevo = ordenados.get(i);
            if (!actual.getConcept().equals(nuevo.concepto())
                    || actual.getAmount().compareTo(nuevo.monto()) != 0
                    || !actual.getDueDate().equals(nuevo.vence())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  Auxiliares
    // ------------------------------------------------------------------

    private record CargoLeido(String concepto, String periodo, BigDecimal monto, LocalDate vence) {}

    /**
     * Cuantos meses distintos se deben. No es lo mismo que cuantos cargos: el
     * arriendo y el gasto comun de septiembre son dos cargos de un solo mes.
     * El mes es el {@code periodo} del cargo, o el de su vencimiento si no lo
     * trae.
     */
    private static long mesesImpagos(List<CargoLeido> cargos) {
        return cargos.stream()
                .map(c -> vacio(c.periodo()) ? YearMonth.from(c.vence()).toString() : c.periodo())
                .distinct()
                .count();
    }

    /** Los tramos de crm_portfoliohandover en APOFYX, para que los numeros calcen. */
    static String tramo(long dias) {
        if (dias <= 30) {
            return "1-30";
        }
        if (dias <= 90) {
            return "31-90";
        }
        if (dias <= 120) {
            return "91-120";
        }
        return ">120";
    }

    private static ErrorDeuda error(String campo, String codigo, String mensaje) {
        return new ErrorDeuda(campo, codigo, mensaje);
    }

    private static ResultadoDeuda rechazo(String idDeuda, ErrorDeuda error) {
        return rechazo(idDeuda, List.of(error));
    }

    private static ResultadoDeuda rechazo(String idDeuda, List<ErrorDeuda> errores) {
        return ResultadoDeuda.rechazada(idDeuda, errores);
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }

    private static String texto(JsonNode nodo) {
        return nodo == null || nodo.isNull() ? null : nodo.asText();
    }

    private static LocalDate fecha(JsonNode nodo) {
        try {
            return LocalDate.parse(texto(nodo));
        } catch (Exception e) {
            return null;
        }
    }

    /** Devuelve el monto, o null si no sirve para la moneda que se declaro. */
    private static BigDecimal monto(JsonNode nodo, String moneda) {
        if (nodo == null || !nodo.isNumber()) {
            return null;
        }
        BigDecimal monto = nodo.decimalValue();
        if (monto.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if ("CLP".equals(moneda) && monto.stripTrailingZeros().scale() > 0) {
            return null;
        }
        if (monto.stripTrailingZeros().scale() > 2) {
            return null;
        }
        return monto;
    }

    /** Lo que vino y no se conoce. El receptor es tolerante, pero lo avisa. */
    private static List<String> camposIgnorados(JsonNode payload) {
        Set<String> sobras = new java.util.TreeSet<>();
        payload.fieldNames().forEachRemaining(campo -> {
            if (!Set.of("version", "lote", "deudas").contains(campo)) {
                sobras.add(campo);
            }
        });
        JsonNode lote = payload.get("lote");
        if (lote != null) {
            lote.fieldNames().forEachRemaining(campo -> {
                if (!Set.of("id_externo", "fecha_corte", "emitido_en", "acreedor", "mandato")
                        .contains(campo)) {
                    sobras.add("lote." + campo);
                }
            });
        }
        JsonNode deudas = payload.get("deudas");
        if (deudas != null && deudas.isArray()) {
            for (JsonNode deuda : deudas) {
                deuda.fieldNames().forEachRemaining(campo -> {
                    if (!Set.of("id_externo", "accion", "motivo_retiro", "deudor", "moneda",
                            "concepto", "referencias", "cargos").contains(campo)) {
                        sobras.add("deudas[]." + campo);
                    }
                });
            }
        }
        return List.copyOf(sobras);
    }

    /**
     * Huella estable del contenido.
     *
     * <p>Se ordenan las claves antes de calcularla: dos envios identicos con
     * los campos en otro orden son el mismo lote, y tratarlos como distintos
     * haria fallar el reenvio legitimo.
     */
    private String huella(JsonNode payload) {
        try {
            Object ordenado = ordenar(json.treeToValue(payload, Object.class));
            return Hash.sha256(json.writeValueAsString(ordenado));
        } catch (Exception e) {
            throw new CarteraInvalida("cuerpo_invalido", "No se pudo leer el cuerpo");
        }
    }

    @SuppressWarnings("unchecked")
    private static Object ordenar(Object valor) {
        if (valor instanceof Map<?, ?> mapa) {
            Map<String, Object> ordenado = new TreeMap<>();
            mapa.forEach((clave, contenido) -> ordenado.put(String.valueOf(clave), ordenar(contenido)));
            return ordenado;
        }
        if (valor instanceof List<?> lista) {
            return lista.stream().map(CarteraIntakeService::ordenar).toList();
        }
        return valor;
    }

    private String comoJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** La respuesta que se dio la primera vez, marcada como repetida. */
    private CarteraResponse leerRespuesta(Batch batch) {
        try {
            return json.readValue(batch.getResponse(), CarteraResponse.class).repetida();
        } catch (Exception e) {
            return CarteraResponse.soloRepetida(batch.getExternalId());
        }
    }
}
