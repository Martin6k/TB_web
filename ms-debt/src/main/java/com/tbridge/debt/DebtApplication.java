package com.tbridge.debt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ms-debt: las deudas.
 *
 * <p>Recibe la cartera por el contrato de integracion (Cartera v1), se la
 * muestra al deudor y a la empresa en el portal, calcula los planes de cuotas,
 * aplica los pagos que avisa ms-payments y le devuelve los eventos a quien le
 * entrego la cartera.
 *
 * <p>RabbitMQ no se decide aqui: es la propiedad {@code events.rabbit}
 * (EVENTS_RABBIT), y de ella dependen la cola, el listener y su salud.
 */
@SpringBootApplication(
        scanBasePackages = {"com.tbridge.debt", "com.tbridge.common"},
        exclude = UserDetailsServiceAutoConfiguration.class
)
//  El despachador de eventos y el avance diario de las campanas.
@EnableScheduling
//  La invitacion al deudor sale en otro hilo: no hace esperar a quien entrega la cartera.
@EnableAsync
public class DebtApplication {

    public static void main(String[] args) {
        SpringApplication.run(DebtApplication.class, args);
    }
}
