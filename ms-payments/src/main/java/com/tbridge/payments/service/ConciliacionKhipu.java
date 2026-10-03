package com.tbridge.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cada 30 segundos, se le pregunta a Khipu por los cobros abiertos. Ver
 * {@link PaymentService#conciliarPendientes}.
 *
 * <p>Es lo que registra un pago cuando el deudor cierra la ventana sin volver,
 * y lo que reemplaza al aviso de Khipu en local, donde Khipu no puede llegar.
 */
@Component
public class ConciliacionKhipu {

    private static final Logger log = LoggerFactory.getLogger(ConciliacionKhipu.class);

    private final PaymentService pagos;

    public ConciliacionKhipu(PaymentService pagos) {
        this.pagos = pagos;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void revisar() {
        int cerrados = pagos.conciliarPendientes();
        if (cerrados > 0) {
            log.info("Khipu: {} cobro(s) quedaron cerrados", cerrados);
        }
    }
}
