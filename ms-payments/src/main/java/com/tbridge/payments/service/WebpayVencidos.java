package com.tbridge.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Cada 5 minutos, los cobros de Webpay abandonados quedan vencidos. Ver {@link PaymentService#vencerWebpayAbandonados}. */
@Component
public class WebpayVencidos {

    private static final Logger log = LoggerFactory.getLogger(WebpayVencidos.class);

    private final PaymentService pagos;
    private final Duration venceEn;

    public WebpayVencidos(PaymentService pagos, @Value("${app.transbank.vence-en:15m}") Duration venceEn) {
        this.pagos = pagos;
        this.venceEn = venceEn;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void revisar() {
        int vencidos = pagos.vencerWebpayAbandonados(venceEn);
        if (vencidos > 0) {
            log.info("Webpay: {} cobro(s) abandonado(s) quedaron vencidos", vencidos);
        }
    }
}
