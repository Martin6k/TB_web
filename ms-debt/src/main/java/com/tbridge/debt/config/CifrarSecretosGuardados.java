package com.tbridge.debt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Al arrancar, cifra los secretos que quedaron en claro de antes de cifrar.
 *
 * <p>Lo hace con SQL sobre la columna y no por la entidad: asi no depende del
 * convertidor, y una base que ya estaba cifrada no se toca (la consulta no
 * encuentra nada). Corre en cada arranque porque es barato y porque una
 * version anterior del servicio, en un despliegue a medias, podria haber
 * escrito alguno en claro.
 */
@Component
public class CifrarSecretosGuardados implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CifrarSecretosGuardados.class);

    private final JdbcTemplate jdbc;
    private final Cifrado cifrado;

    public CifrarSecretosGuardados(JdbcTemplate jdbc, Cifrado cifrado) {
        this.jdbc = jdbc;
        this.cifrado = cifrado;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> enClaro = jdbc.queryForList(
                "SELECT id, secret FROM subscriptions WHERE secret NOT LIKE ?", Cifrado.PREFIJO + "%");
        for (Map<String, Object> fila : enClaro) {
            jdbc.update("UPDATE subscriptions SET secret = ? WHERE id = ?",
                    cifrado.cifrar((String) fila.get("secret")), fila.get("id"));
        }
        if (!enClaro.isEmpty()) {
            log.info("Cifrados {} secretos de suscripciones que estaban en claro", enClaro.size());
        }
    }
}
