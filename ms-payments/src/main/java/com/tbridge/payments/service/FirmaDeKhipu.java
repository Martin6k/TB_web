package com.tbridge.payments.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * La firma de los avisos de Khipu.
 *
 * <pre>
 *   x-khipu-signature: t=1711965600393,s=GYzpjnXl...=
 *   s = base64( HMAC-SHA256( secreto de la cuenta de cobro, t + "." + cuerpo ) )
 * </pre>
 *
 * <p>El cuerpo se firma tal como llego: reordenarlo o volver a serializarlo
 * cambia la firma. No se exige que {@code t} sea reciente: un aviso no se
 * aplica por lo que dice, sino que dispara una consulta a Khipu, asi que
 * repetirlo no cobra nada.
 */
@Component
public class FirmaDeKhipu {

    private final String secreto;

    public FirmaDeKhipu(@Value("${app.khipu.secreto:}") String secreto) {
        this.secreto = secreto == null ? "" : secreto.trim();
    }

    /** Sin secreto no se puede verificar: los avisos se ignoran y manda la consulta periodica. */
    public boolean configurada() {
        return !secreto.isEmpty();
    }

    public boolean valida(String encabezado, String cuerpo) {
        if (!configurada() || encabezado == null || cuerpo == null) {
            return false;
        }
        String t = null;
        String s = null;
        for (String parte : encabezado.split(",")) {
            String[] par = parte.trim().split("=", 2);
            if (par.length == 2 && par[0].equals("t")) t = par[1];
            if (par.length == 2 && par[0].equals("s")) s = par[1];
        }
        if (t == null || s == null) {
            return false;
        }
        byte[] esperada = firmar(t + "." + cuerpo);
        byte[] recibida;
        try {
            recibida = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(esperada, recibida);
    }

    byte[] firmar(String texto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("La JVM no tiene HmacSHA256", e);
        }
    }
}
