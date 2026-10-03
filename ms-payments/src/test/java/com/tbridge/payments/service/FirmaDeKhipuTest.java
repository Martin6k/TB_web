package com.tbridge.payments.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La firma de los avisos de Khipu: {@code t=<ms>,s=base64(HMAC-SHA256(secreto, t + "." + cuerpo))}. */
class FirmaDeKhipuTest {

    private static final String CUERPO = "{\"payment_id\":\"gqzdy6chjne9\",\"amount\":\"410000.0000\"}";

    private final FirmaDeKhipu firma = new FirmaDeKhipu("secreto-de-la-cuenta");

    private String encabezado(String t, String cuerpo) {
        return "t=" + t + ",s=" + Base64.getEncoder().encodeToString(firma.firmar(t + "." + cuerpo));
    }

    @Test
    void la_firma_de_khipu_sobre_el_cuerpo_tal_como_llego_es_valida() {
        assertTrue(firma.valida(encabezado("1711965600393", CUERPO), CUERPO));
        assertTrue(firma.valida(" t=1711965600393 , s=" + encabezado("1711965600393", CUERPO).split("s=")[1], CUERPO),
                "acepta espacios entre las partes");
    }

    @Test
    void otro_cuerpo_otra_marca_u_otro_secreto_no_valen() {
        String buena = encabezado("1711965600393", CUERPO);
        assertFalse(firma.valida(buena, CUERPO.replace("410000", "1")), "un cuerpo cambiado");
        assertFalse(firma.valida(buena.replace("t=1711965600393", "t=1711965600394"), CUERPO), "otra marca de tiempo");
        assertFalse(new FirmaDeKhipu("otro-secreto").valida(buena, CUERPO), "otro secreto");
    }

    @Test
    void sin_secreto_o_sin_firma_nada_es_valido() {
        assertFalse(new FirmaDeKhipu("").valida(encabezado("1", CUERPO), CUERPO));
        assertFalse(new FirmaDeKhipu("").configurada());
        assertFalse(firma.valida(null, CUERPO));
        assertFalse(firma.valida("t=1", CUERPO));
        assertFalse(firma.valida("t=1,s=no-es-base64!", CUERPO));
    }
}
