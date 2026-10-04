package com.tbridge.debt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.common.events.PagoConfirmado;
import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.evento.DeudaSaldadaDatos;
import com.tbridge.debt.dto.evento.PagoConfirmadoDatos;
import com.tbridge.debt.dto.evento.RepactacionAceptadaDatos;
import com.tbridge.debt.dto.response.DebtDetailResponse;
import com.tbridge.debt.dto.response.DebtSnapshotResponse;
import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.dto.response.InstallmentPreview;
import com.tbridge.debt.dto.response.RepactPlan;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.DetallePago;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.repository.RepactationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Las deudas.
 *
 * <p><b>Toda consulta de una empresa pasa por su organizacion.</b> Antes esto
 * era {@code findAll()} para cualquiera con rol CREDITOR, asi que todos veian
 * las carteras de todos.
 *
 * <p><b>El saldo se calcula.</b> Sale de las cuotas pendientes, nunca de una
 * columna guardada: un total almacenado al lado de sus partes termina
 * discrepando de ellas.
 */
@Service
//  open-in-view esta apagado a proposito: la sesion de Hibernate no sigue
//  abierta mientras se arma la respuesta. Por eso las lecturas tambien van en
//  una transaccion, o las relaciones perezosas revientan al tocarlas.
@Transactional(readOnly = true)
public class DebtService {

    private static final Logger log = LoggerFactory.getLogger(DebtService.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    /** El orden en que se pagan las cuotas: la que vence primero, primero. */
    static final Comparator<Installment> EN_ORDEN =
            Comparator.comparing(Installment::getDueDate).thenComparing(Installment::getNumber);

    private final DebtRepository debts;
    private final DebtorRepository debtors;
    private final OrganizationRepository organizations;
    private final DebtChargeRepository charges;
    private final InstallmentRepository installments;
    private final RepactationRepository repactations;
    private final DebtEventRepository events;
    private final RepactationService repactation;
    private final EventosService eventos;
    private final ObjectMapper json;

    public DebtService(
            DebtRepository debts,
            DebtorRepository debtors,
            OrganizationRepository organizations,
            DebtChargeRepository charges,
            InstallmentRepository installments,
            RepactationRepository repactations,
            DebtEventRepository events,
            RepactationService repactation,
            EventosService eventos,
            ObjectMapper json
    ) {
        this.debts = debts;
        this.debtors = debtors;
        this.organizations = organizations;
        this.charges = charges;
        this.installments = installments;
        this.repactations = repactations;
        this.events = events;
        this.repactation = repactation;
        this.eventos = eventos;
        this.json = json;
    }

    // ------------------------------------------------------------------
    //  Quien esta preguntando
    // ------------------------------------------------------------------

    /**
     * La organizacion detras de la sesion de una empresa.
     *
     * <p>Se resuelve por RUT. Sin RUT en el token no hay forma de acotar la
     * cartera, y devolver "todas" seria repetir la fuga que este rediseno vino
     * a cerrar.
     */
    public Organization organizacionDe(JwtPrincipal user) {
        if (user == null || user.rut() == null || user.rut().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "El token no dice de que empresa eres: no se puede mostrar una cartera");
        }
        return organizations.findByRut(user.rut())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "Esa empresa no esta registrada en DataBridge"));
    }

    /** El deudor detras del token, por RUT: es lo unico que trae su sesion. */
    Debtor deudorDe(JwtPrincipal user) {
        if (user == null || user.rut() == null || user.rut().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "La sesion no identifica al deudor");
        }
        return debtors.findByRut(user.rut())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay deudas a tu nombre"));
    }

    // ------------------------------------------------------------------
    //  Consultas
    // ------------------------------------------------------------------

    /**
     * Lo que ve cada quien: la empresa, su cartera; el deudor, lo suyo. Cuando
     * el que mira es el deudor, se anota que entro (ver {@link #anotarIngreso}).
     */
    @Transactional
    public List<DebtSummaryResponse> listFor(JwtPrincipal user) {
        if (user != null && user.isCreditor()) {
            List<Debt> cartera = debts.carteraDe(organizacionDe(user));
            //  Cuando se le envio el codigo a cada deudor, en una sola consulta:
            //  la empresa ve a quien falta invitar o hay que reenviarselo.
            Map<Long, Instant> enviado = new HashMap<>();
            if (!cartera.isEmpty()) {
                for (DebtEvent evento : events.findByDebtInAndType(cartera, DebtEvent.Type.code_sent)) {
                    enviado.merge(evento.getDebt().getId(), evento.getOccurredAt(),
                            (a, b) -> a.isAfter(b) ? a : b);
                }
            }
            Map<Long, DebtSummaryResponse.Disputa> disputas = disputas(cartera);
            return cartera.stream()
                    .map(d -> DebtSummaryResponse.from(d, installments.findByDebtOrderByNumberAsc(d),
                            enviado.get(d.getId()), disputas.get(d.getId())))
                    .toList();
        }
        List<Debt> filas = debts.findByDebtorOrderByUpdatedAtDesc(deudorDe(user));
        filas.forEach(this::anotarIngreso);
        Map<Long, DebtSummaryResponse.Disputa> disputas = disputas(filas);
        return filas.stream()
                .map(d -> DebtSummaryResponse.from(d, installments.findByDebtOrderByNumberAsc(d), null,
                        disputas.get(d.getId())))
                .toList();
    }

    /** La disputa abierta de cada deuda que esta en disputa: la ultima que abrio el deudor. */
    private Map<Long, DebtSummaryResponse.Disputa> disputas(List<Debt> deudas) {
        List<Debt> enDisputa = deudas.stream().filter(d -> d.getStatus() == Debt.Status.disputed).toList();
        Map<Long, DebtSummaryResponse.Disputa> porDeuda = new HashMap<>();
        if (enDisputa.isEmpty()) {
            return porDeuda;
        }
        for (DebtEvent evento : events.findByDebtInAndType(enDisputa, DebtEvent.Type.disputed)) {
            DebtSummaryResponse.Disputa disputa = new DebtSummaryResponse.Disputa(evento.getReference(),
                    detalleDe(evento), evento.getOccurredAt());
            porDeuda.merge(evento.getDebt().getId(), disputa, (a, b) -> a.desde().isAfter(b.desde()) ? a : b);
        }
        return porDeuda;
    }

    private String detalleDe(DebtEvent evento) {
        if (evento.getDetail() == null) {
            return null;
        }
        try {
            return json.readTree(evento.getDetail()).path("detalle").asText(null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Las deudas que ve quien pregunta, sin mas: la empresa, su cartera; el
     * deudor, las suyas. Para los servicios que arman sus propias vistas
     * (historial, vencimientos, convenios en riesgo).
     */
    List<Debt> deudasVisibles(JwtPrincipal user) {
        if (user != null && user.isCreditor()) {
            return debts.carteraDe(organizacionDe(user));
        }
        return debts.findByDebtorOrderByUpdatedAtDesc(deudorDe(user));
    }

    /**
     * El deudor entro a ver su deuda. Se anota una vez al dia por deuda: es lo
     * que la agencia mide como "ingresos al portal" y, con codigo de acceso,
     * reemplaza al clic en el enlace que ya no existe.
     */
    private void anotarIngreso(Debt deuda) {
        Instant desdeHoy = LocalDate.now(CHILE).atStartOfDay(CHILE).toInstant();
        if (!events.existsByDebtAndTypeAndOccurredAtAfter(deuda, DebtEvent.Type.portal_entered, desdeHoy)) {
            events.save(DebtEvent.de(deuda, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor));
        }
    }

    public DebtDetailResponse getFor(JwtPrincipal user, Long id) {
        return detalle(requireVisible(user, id));
    }

    /**
     * La deuda, si a quien pregunta le corresponde verla. La empresa solo ve
     * las de su cartera; el deudor, solo las propias.
     */
    public Debt requireVisible(JwtPrincipal user, Long id) {
        Debt deuda = debts.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deuda no encontrada"));
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        if (user.isCreditor()) {
            if (!opera(organizacionDe(user), deuda)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Esa deuda no es de tu cartera");
            }
            return deuda;
        }
        if (!deuda.getDebtor().getId().equals(deudorDe(user).getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No puedes ver esta deuda");
        }
        return deuda;
    }

    /** La misma regla que {@code DebtRepository.carteraDe}, para una deuda suelta. */
    private static boolean opera(Organization organizacion, Debt deuda) {
        return deuda.getCreditor().getId().equals(organizacion.getId())
                || deuda.getLastBatch().getSender().getId().equals(organizacion.getId());
    }

    /** Lo que se debe hoy: la suma de las cuotas pendientes. */
    public BigDecimal saldo(Debt deuda) {
        return installments.findByDebtAndStatus(deuda, Installment.Status.pending).stream()
                .map(Installment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------
    //  Repactacion
    // ------------------------------------------------------------------

    public RepactPlan simulate(JwtPrincipal user, Long id, int months) {
        Debt deuda = requireVisible(user, id);
        return repactation.simulate(saldo(deuda), deuda.getCurrency(), months, LocalDate.now().plusMonths(1));
    }

    /**
     * El deudor acepta un plan.
     *
     * <p>Las cuotas pendientes se ANULAN, no se borran: un compromiso que
     * existio deja rastro. Y el plan anterior queda marcado como reemplazado,
     * por la misma razon.
     */
    @Transactional
    public DebtDetailResponse applyRepact(JwtPrincipal user, Long id, int months) {
        if (user != null && user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La repactacion la acepta el deudor");
        }
        Debt deuda = requireVisible(user, id);
        if (deuda.getStatus() == Debt.Status.paid) {
            throw new ApiException(HttpStatus.CONFLICT, "La deuda ya esta pagada");
        }
        if (deuda.getStatus() == Debt.Status.withdrawn) {
            throw new ApiException(HttpStatus.CONFLICT, "El acreedor retiro esta deuda de la cobranza");
        }
        if (deuda.getStatus() == Debt.Status.disputed) {
            throw new ApiException(HttpStatus.CONFLICT, "La deuda esta en revision: no se repacta mientras tanto");
        }

        RepactPlan plan = repactation.simulate(saldo(deuda), deuda.getCurrency(), months, LocalDate.now().plusMonths(1));

        for (Repactation anterior : repactations.findByDebtAndSupersededAtIsNull(deuda)) {
            anterior.setSupersededAt(Instant.now());
            repactations.save(anterior);
        }
        Repactation nueva = new Repactation();
        nueva.setDebt(deuda);
        nueva.setMonths((short) months);
        nueva.setMonthlyAmount(plan.monthlyAmount());
        nueva.setCurrency(deuda.getCurrency());
        repactations.save(nueva);

        anularPendientes(deuda);
        short numero = siguienteNumero(deuda);
        for (InstallmentPreview previa : plan.cuotas()) {
            Installment cuota = new Installment();
            cuota.setDebt(deuda);
            cuota.setRepactation(nueva);
            cuota.setNumber(numero++);
            cuota.setDueDate(previa.dueDate());
            cuota.setAmount(previa.amount());
            cuota.setStatus(Installment.Status.pending);
            installments.save(cuota);
        }

        deuda.setStatus(Debt.Status.repacted);
        deuda.setUpdatedAt(Instant.now());
        debts.save(deuda);

        events.save(DebtEvent.de(deuda, DebtEvent.Type.repacted, DebtEvent.Actor.debtor)
                .conMonto(plan.monthlyAmount(), deuda.getCurrency())
                .conReferencia(months + " cuotas"));
        eventos.publicar(deuda, EventosService.REPACTACION_ACEPTADA, new RepactacionAceptadaDatos(
                deuda.getExternalId(), months, EventosService.monto(plan.monthlyAmount(), deuda.getCurrency()),
                deuda.getCurrency().name(), plan.cuotas().getFirst().dueDate().toString()), Instant.now());

        return detalle(deuda);
    }

    private void anularPendientes(Debt deuda) {
        for (Installment pendiente : installments.findByDebtAndStatus(deuda, Installment.Status.pending)) {
            pendiente.setStatus(Installment.Status.void_);
            installments.save(pendiente);
        }
    }

    /** Las cuotas se numeran corrido, para que dos planes no choquen. */
    private short siguienteNumero(Debt deuda) {
        return (short) (installments.findByDebtOrderByNumberAsc(deuda).stream()
                .mapToInt(Installment::getNumber).max().orElse(0) + 1);
    }

    // ------------------------------------------------------------------
    //  Lo que ms-payments necesita y lo que avisa
    // ------------------------------------------------------------------

    /**
     * Cuanto se debe y a quien, para que ms-payments no le crea al navegador.
     *
     * <p><b>Las cuotas se pagan en orden.</b> Se pueden pagar varias a la vez,
     * pero siempre las que vencen primero: pagar la de diciembre dejando
     * octubre impaga dejaria al deudor en mora con plata pagada. Sin cuotas
     * indicadas se cobran todas, es decir, el saldo.
     *
     * <p>La cuota va en la respuesta solo cuando se paga una. Con varias va
     * vacia, y al confirmarse el pago se imputa de la mas antigua a la mas
     * nueva: como el monto es justo la suma de las elegidas, paga esas y
     * ninguna otra.
     */
    public DebtSnapshotResponse snapshotInterno(Long debtId, List<Long> installmentIds) {
        Debt deuda = debts.findById(debtId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deuda no encontrada"));
        //  Mientras la empresa revisa la disputa, no se cobra.
        if (deuda.getStatus() == Debt.Status.disputed) {
            throw new ApiException(HttpStatus.CONFLICT, "La deuda esta en revision: no se puede pagar mientras tanto");
        }
        List<Installment> pendientes = installments.findByDebtAndStatus(deuda, Installment.Status.pending).stream()
                .sorted(EN_ORDEN).toList();

        List<Installment> aPagar = pendientes;
        if (installmentIds != null && !installmentIds.isEmpty()) {
            Set<Long> pedidas = new HashSet<>(installmentIds);
            if (pedidas.size() != installmentIds.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Una cuota viene dos veces");
            }
            Map<Long, Installment> pendientePorId = pendientes.stream()
                    .collect(Collectors.toMap(Installment::getId, Function.identity()));
            for (Long id : installmentIds) {
                if (!pendientePorId.containsKey(id)) {
                    throw cuotaQueNoSePuedePagar(deuda, id);
                }
            }
            aPagar = pendientes.subList(0, pedidas.size());
            if (!aPagar.stream().map(Installment::getId).collect(Collectors.toSet()).equals(pedidas)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Las cuotas se pagan en orden, desde la que vence primero");
            }
        }

        BigDecimal monto = aPagar.stream().map(Installment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Esa deuda no tiene saldo por pagar");
        }
        Long cuotaId = aPagar.size() == 1 ? aPagar.getFirst().getId() : null;
        return new DebtSnapshotResponse(deuda.getId(), deuda.getCreditor().getRut(), deuda.getDebtor().getRut(),
                deuda.getCurrency().name(), monto, cuotaId);
    }

    /** Por que una cuota pedida no esta entre las que se pueden pagar. */
    private ApiException cuotaQueNoSePuedePagar(Debt deuda, Long id) {
        Installment cuota = installments.findById(id).orElse(null);
        if (cuota == null) {
            return new ApiException(HttpStatus.NOT_FOUND, "Cuota no encontrada");
        }
        if (!cuota.getDebt().getId().equals(deuda.getId())) {
            return new ApiException(HttpStatus.BAD_REQUEST, "Esa cuota no es de esa deuda");
        }
        return new ApiException(HttpStatus.CONFLICT, "La cuota " + cuota.getNumber() + " ya no esta pendiente");
    }

    /**
     * Un pago confirmado por ms-payments.
     *
     * <p>Idempotente a proposito: el aviso se entrega "al menos una vez", asi
     * que el mismo pago puede llegar dos veces y la segunda no debe abonar de
     * nuevo. Se reconoce por la referencia de la pasarela, que ya quedo
     * guardada en el evento.
     */
    @Transactional
    public void onPagoConfirmado(PagoConfirmado aviso) {
        if (aviso == null || aviso.debtId() == null) {
            return;
        }
        Debt deuda = debts.findById(aviso.debtId()).orElse(null);
        if (deuda == null) {
            log.warn("Aviso de pago para una deuda que no existe: {}", aviso.debtId());
            return;
        }

        String referencia = aviso.gateway() + ":" + aviso.gatewayTxnId();
        boolean yaAplicado = events.findByDebtOrderByOccurredAtAsc(deuda).stream()
                .anyMatch(e -> e.getType() == DebtEvent.Type.payment_applied && referencia.equals(e.getReference()));
        if (yaAplicado) {
            log.info("Aviso repetido del pago {}: no se abona de nuevo", aviso.paymentId());
            return;
        }

        //  Las cuotas vigentes, en el orden en que se pagan: de aca sale el lugar
        //  de cada una ("la 4 de 6") para el historial del deudor.
        List<Installment> vigentes = installments.findByDebtOrderByNumberAsc(deuda).stream()
                .filter(c -> c.getStatus() != Installment.Status.void_)
                .sorted(EN_ORDEN).toList();
        List<Installment> pagadas = new ArrayList<>();

        if (aviso.installmentId() != null) {
            installments.findById(aviso.installmentId())
                    .filter(c -> c.getDebt().getId().equals(deuda.getId()))
                    .filter(c -> c.getStatus() == Installment.Status.pending)
                    .ifPresent(cuota -> {
                        marcarPagada(cuota, aviso.paidAt());
                        pagadas.add(cuota);
                    });
        } else {
            //  Sin cuota indicada, se imputa de la mas antigua a la mas nueva,
            //  como cualquier abono en una cuenta corriente.
            BigDecimal porAplicar = aviso.amount() == null ? BigDecimal.ZERO : aviso.amount();
            List<Installment> pendientes = installments.findByDebtAndStatus(deuda, Installment.Status.pending).stream()
                    .sorted(EN_ORDEN).toList();
            for (Installment cuota : pendientes) {
                if (porAplicar.compareTo(cuota.getAmount()) < 0) {
                    break;
                }
                marcarPagada(cuota, aviso.paidAt());
                pagadas.add(cuota);
                porAplicar = porAplicar.subtract(cuota.getAmount());
            }
        }

        Debt.Currency moneda = Debt.Currency.valueOf(aviso.currency());
        Instant pagadoEn = aviso.paidAt() == null ? Instant.now() : aviso.paidAt();
        //  Fechado cuando se pago, no cuando llego el aviso: el aviso puede
        //  tardar (reintentos) y el historial cuenta cuando pago el deudor.
        DebtEvent aplicado = DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                .conMonto(aviso.amount(), moneda)
                .conReferencia(referencia)
                .conDetalle(detalleDelPago(deuda, aviso, vigentes, pagadas));
        aplicado.setOccurredAt(pagadoEn);
        events.save(aplicado);
        eventos.publicar(deuda, EventosService.PAGO_CONFIRMADO, datosDelPago(deuda, aviso, moneda, pagadoEn), pagadoEn);

        if (saldo(deuda).compareTo(BigDecimal.ZERO) == 0) {
            deuda.setStatus(Debt.Status.paid);
            events.save(DebtEvent.de(deuda, DebtEvent.Type.settled, DebtEvent.Actor.system));
            eventos.publicar(deuda, EventosService.DEUDA_SALDADA,
                    new DeudaSaldadaDatos(deuda.getExternalId(), EventosService.enChile(pagadoEn)), pagadoEn);
        }
        deuda.setUpdatedAt(Instant.now());
        debts.save(deuda);
    }

    /**
     * Lo que el acreedor necesita para imputar el pago en su propio sistema.
     * En UF va el valor usado: sin el, nadie podria reconstruir por que UF
     * 38,5 fueron esos pesos.
     */
    private static PagoConfirmadoDatos datosDelPago(Debt deuda, PagoConfirmado aviso, Debt.Currency moneda,
                                                    Instant pagadoEn) {
        return new PagoConfirmadoDatos(
                deuda.getExternalId(),
                String.valueOf(aviso.paymentId()),
                EventosService.monto(aviso.amount(), moneda),
                moneda.name(),
                aviso.amountClp(),
                moneda == Debt.Currency.UF ? aviso.ufValue() : null,
                aviso.gateway() == null ? null : aviso.gateway().toLowerCase(),
                EventosService.enChile(pagadoEn));
    }

    /**
     * El {@link DetallePago} de este aviso, en JSON. Si no se puede escribir, el pago igual se aplica.
     *
     * <p>En convenio, el lugar de cada cuota se cuenta dentro del plan ("la 4 de 6"), y una cuota aparte
     * se cuenta aparte. Sin convenio, el pago es el total de la deuda.
     */
    private String detalleDelPago(Debt deuda, PagoConfirmado aviso, List<Installment> vigentes,
                                  List<Installment> pagadas) {
        List<Long> plan = deuda.getStatus() != Debt.Status.repacted ? List.of()
                : vigentes.stream().filter(Installment::enConvenio).map(Installment::getId).toList();
        List<Integer> lugares;
        int de;
        long fuera = 0;
        if (plan.isEmpty()) {
            lugares = pagadas.isEmpty() ? List.of() : List.of(1);
            de = 1;
        } else {
            lugares = pagadas.stream()
                    .filter(Installment::enConvenio)
                    .map(c -> plan.indexOf(c.getId()) + 1)
                    .filter(lugar -> lugar > 0)
                    .sorted()
                    .toList();
            de = plan.size();
            fuera = pagadas.stream().filter(c -> !c.enConvenio()).count();
        }
        DetallePago detalle = new DetallePago(aviso.paymentId(), aviso.amountClp(), aviso.ufValue(),
                aviso.gateway() == null ? null : aviso.gateway().toLowerCase(Locale.ROOT), lugares, de,
                fuera == 0 ? null : (int) fuera);
        try {
            return json.writeValueAsString(detalle);
        } catch (JsonProcessingException e) {
            log.warn("No se pudo guardar el detalle del pago {}: {}", aviso.paymentId(), e.getMessage());
            return null;
        }
    }

    private void marcarPagada(Installment cuota, Instant cuando) {
        cuota.setStatus(Installment.Status.paid);
        cuota.setPaidAt(cuando == null ? Instant.now() : cuando);
        installments.save(cuota);
    }

    // ------------------------------------------------------------------
    //  Representacion
    // ------------------------------------------------------------------

    /** Con una sola consulta de cuotas: el saldo y el avance salen de la misma lista. */
    private DebtSummaryResponse resumen(Debt deuda) {
        return DebtSummaryResponse.from(deuda, installments.findByDebtOrderByNumberAsc(deuda), null,
                disputas(List.of(deuda)).get(deuda.getId()));
    }

    private DebtDetailResponse detalle(Debt deuda) {
        return new DebtDetailResponse(
                resumen(deuda),
                charges.findByDebtOrderByDueDateAsc(deuda).stream().map(DebtDetailResponse.Cargo::from).toList(),
                installments.findByDebtOrderByNumberAsc(deuda).stream().map(DebtDetailResponse.Cuota::from).toList(),
                events.findByDebtOrderByOccurredAtAsc(deuda).stream().map(DebtDetailResponse.Suceso::from).toList());
    }
}
