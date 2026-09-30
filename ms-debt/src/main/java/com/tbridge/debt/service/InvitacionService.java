package com.tbridge.debt.service;

import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * La invitacion: el primer codigo de acceso, apenas una deuda entra en cobranza.
 *
 * <p>DataBridge detecta al moroso cuando llega la cartera, y con esto el deudor
 * se entera sin que nadie de la agencia tenga que apretar un boton. El correo
 * es el primer aviso de siempre, sin monto ni enlace. El boton "Enviar codigo"
 * del portal sigue ahi para reenviarlo.
 *
 * <p>Sale despues del commit del lote y en otro hilo: un lote que se deshace no
 * invita a nadie, y mil deudas nuevas no hacen esperar a quien entrega la
 * cartera. Si ms-auth no responde, la deuda queda igual y la agencia envia el
 * codigo a mano.
 */
@Service
public class InvitacionService {

    private static final Logger log = LoggerFactory.getLogger(InvitacionService.class);

    /** Lo que publica la ingesta: esta deuda acaba de entrar, o de volver, a cobranza. */
    public record DeudaEnCobranza(Long debtId) {}

    private final DebtRepository debts;
    private final DebtEventRepository events;
    private final AuthClient auth;

    public InvitacionService(DebtRepository debts, DebtEventRepository events, AuthClient auth) {
        this.debts = debts;
        this.events = events;
        this.auth = auth;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invitar(DeudaEnCobranza aviso) {
        Debt deuda = debts.findById(aviso.debtId()).orElse(null);
        if (deuda == null || deuda.getStatus() != Debt.Status.open) {
            return;
        }
        Debtor deudor = deuda.getDebtor();
        if (deudor.getEmail() == null || deudor.getEmail().isBlank()) {
            //  Sin correo no hay por donde: el aviso por WhatsApp aun no esta
            //  conectado. La deuda queda sin invitar, y el portal lo muestra.
            return;
        }
        try {
            auth.emitirCodigo(new AuthClient.PedidoDeCodigo(deudor.getRut(), List.of("correo"),
                    deudor.getEmail(), deuda.getCreditor().getTradeName(), deuda.getExternalId()));
        } catch (RuntimeException e) {
            log.warn("No se pudo invitar al deudor de la deuda {}: {}", deuda.getId(), e.getMessage());
            return;
        }
        events.save(DebtEvent.de(deuda, DebtEvent.Type.code_sent, DebtEvent.Actor.system)
                .conReferencia(AccesoService.enmascarar(deudor.getEmail())));
    }
}
