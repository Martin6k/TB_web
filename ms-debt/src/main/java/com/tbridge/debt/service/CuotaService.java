package com.tbridge.debt.service;

import com.tbridge.common.exception.ApiException;
import com.tbridge.common.jwt.JwtPrincipal;
import com.tbridge.debt.dto.response.ConvenioEnRiesgoResponse;
import com.tbridge.debt.dto.response.CuotaPorVencerResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.repository.InstallmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Las cuotas mirando al calendario: lo que le viene al deudor, y los convenios
 * que la empresa tiene que atender antes de que se caigan.
 */
@Service
@Transactional(readOnly = true)
public class CuotaService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final DebtService debts;
    private final InstallmentRepository installments;

    public CuotaService(DebtService debts, InstallmentRepository installments) {
        this.debts = debts;
        this.installments = installments;
    }

    /**
     * Todas las cuotas por pagar del deudor, de la que vence primero a la
     * ultima. Una deuda sin convenio aparece como una sola cuota por el total,
     * y ya vencida: es mora.
     */
    public List<CuotaPorVencerResponse> vencimientos(JwtPrincipal user) {
        if (user == null || user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Los vencimientos son del deudor");
        }
        LocalDate hoy = LocalDate.now(CHILE);
        List<CuotaPorVencerResponse> filas = new ArrayList<>();
        for (Debt deuda : debts.deudasVisibles(user)) {
            if (deuda.getStatus() != Debt.Status.open && deuda.getStatus() != Debt.Status.repacted) {
                continue;
            }
            //  El lugar se cuenta dentro del convenio: una cuota aparte (un mes que
            //  el acreedor informo despues) no corre la numeracion del plan.
            List<Installment> vigentes = vigentes(deuda);
            List<Installment> delPlan = vigentes.stream().filter(Installment::enConvenio).toList();
            boolean hayConvenio = deuda.getStatus() == Debt.Status.repacted && !delPlan.isEmpty();
            for (Installment cuota : vigentes) {
                if (cuota.getStatus() != Installment.Status.pending) {
                    continue;
                }
                boolean delConvenio = cuota.enConvenio();
                filas.add(new CuotaPorVencerResponse(cuota.getId(), deuda.getId(), deuda.getExternalId(),
                        deuda.getCreditor().getTradeName(), deuda.getConcept(), deuda.getCurrency(),
                        cuota.getAmount(), cuota.getDueDate(),
                        delConvenio ? delPlan.indexOf(cuota) + 1 : 0, delConvenio ? delPlan.size() : 0,
                        ChronoUnit.DAYS.between(hoy, cuota.getDueDate()), cuota.getDueDate().isBefore(hoy),
                        delConvenio, hayConvenio && !delConvenio));
            }
        }
        filas.sort(Comparator.comparing(CuotaPorVencerResponse::vencimiento));
        return filas;
    }

    /**
     * Los convenios de la cartera con cuotas vencidas sin pagar, del mas
     * atrasado al menos. Una deuda sin convenio no esta aca: esa es mora de
     * origen, y ya se ve en la cartera como pendiente.
     */
    public List<ConvenioEnRiesgoResponse> enRiesgo(JwtPrincipal user) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Los convenios en riesgo son de la cartera de la empresa");
        }
        LocalDate hoy = LocalDate.now(CHILE);
        List<ConvenioEnRiesgoResponse> filas = new ArrayList<>();
        for (Debt deuda : debts.deudasVisibles(user)) {
            if (deuda.getStatus() != Debt.Status.repacted) {
                continue;
            }
            //  Solo las cuotas del plan: el riesgo es que el deudor deje de pagar su convenio.
            List<Installment> vigentes = vigentes(deuda).stream().filter(Installment::enConvenio).toList();
            List<Installment> vencidas = vigentes.stream()
                    .filter(c -> c.getStatus() == Installment.Status.pending && c.getDueDate().isBefore(hoy))
                    .toList();
            if (vencidas.isEmpty()) {
                continue;
            }
            LocalDate desde = vencidas.getFirst().getDueDate();
            Instant ultimoPago = vigentes.stream().map(Installment::getPaidAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            filas.add(new ConvenioEnRiesgoResponse(deuda.getId(), deuda.getExternalId(),
                    deuda.getCreditor().getTradeName(), deuda.getDebtor().getFullName(), deuda.getDebtor().getRut(),
                    deuda.getCurrency(), vencidas.size(), suma(vencidas), desde, ChronoUnit.DAYS.between(desde, hoy),
                    (int) vigentes.stream().filter(c -> c.getStatus() == Installment.Status.paid).count(),
                    vigentes.size(),
                    suma(vigentes.stream().filter(c -> c.getStatus() == Installment.Status.pending).toList()),
                    ultimoPago));
        }
        filas.sort(Comparator.comparingLong(ConvenioEnRiesgoResponse::diasAtraso).reversed());
        return filas;
    }

    /** Las cuotas que cuentan (las anuladas no), en el orden en que se pagan. */
    private List<Installment> vigentes(Debt deuda) {
        return installments.findByDebtOrderByNumberAsc(deuda).stream()
                .filter(c -> c.getStatus() != Installment.Status.void_)
                .sorted(DebtService.EN_ORDEN)
                .toList();
    }

    private static BigDecimal suma(List<Installment> cuotas) {
        return cuotas.stream().map(Installment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
