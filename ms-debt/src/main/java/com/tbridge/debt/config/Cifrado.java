package com.tbridge.debt.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra lo que DataBridge guarda y tiene que poder leer de vuelta: el secreto
 * con que firma los avisos de cada suscripcion. Una huella no sirve para
 * firmar, asi que va cifrado con AES-256-GCM, con una llave que viene de
 * {@code CIFRADO_LLAVE} y nunca toca la base: quien se lleve un respaldo no se
 * lleva los secretos.
 *
 * <p>Un valor cifrado es {@code "enc:v1:" + base64(iv | etiqueta | cifrado)},
 * el mismo formato que usan APOFYX y Patrimonio. Uno sin ese prefijo es de
 * antes de cifrar: se lee tal cual, y {@link CifrarSecretosGuardados} lo cifra
 * al arrancar.
 */
@Component
public class Cifrado {

    public static final String PREFIJO = "enc:v1:";
    private static final int IV = 12;
    private static final int ETIQUETA = 16;

    private final SecretKeySpec llave;
    private final SecureRandom azar = new SecureRandom();

    public Cifrado(@Value("${app.cifrado.llave}") String llave) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(llave.getBytes(StandardCharsets.UTF_8));
            this.llave = new SecretKeySpec(bytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("La JVM no tiene SHA-256", e);
        }
    }

    public static boolean estaCifrado(String valor) {
        return valor != null && valor.startsWith(PREFIJO);
    }

    /** El valor cifrado. Vacio o ya cifrado, se devuelve igual. */
    public String cifrar(String valor) {
        if (valor == null || valor.isEmpty() || estaCifrado(valor)) return valor;
        try {
            byte[] iv = new byte[IV];
            azar.nextBytes(iv);
            Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
            cifrador.init(Cipher.ENCRYPT_MODE, llave, new GCMParameterSpec(ETIQUETA * 8, iv));
            //  Java deja la etiqueta al final; se guarda delante del cifrado.
            byte[] sellado = cifrador.doFinal(valor.getBytes(StandardCharsets.UTF_8));
            int largo = sellado.length - ETIQUETA;
            byte[] salida = ByteBuffer.allocate(IV + sellado.length)
                    .put(iv)
                    .put(sellado, largo, ETIQUETA)
                    .put(sellado, 0, largo)
                    .array();
            return PREFIJO + Base64.getEncoder().encodeToString(salida);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar", e);
        }
    }

    /** El valor original. Si no estaba cifrado, se devuelve igual. */
    public String descifrar(String valor) {
        if (!estaCifrado(valor)) return valor;
        try {
            byte[] datos = Base64.getDecoder().decode(valor.substring(PREFIJO.length()));
            byte[] iv = Arrays.copyOfRange(datos, 0, IV);
            byte[] etiqueta = Arrays.copyOfRange(datos, IV, IV + ETIQUETA);
            byte[] cifrado = Arrays.copyOfRange(datos, IV + ETIQUETA, datos.length);
            Cipher descifrador = Cipher.getInstance("AES/GCM/NoPadding");
            descifrador.init(Cipher.DECRYPT_MODE, llave, new GCMParameterSpec(ETIQUETA * 8, iv));
            byte[] sellado = ByteBuffer.allocate(cifrado.length + ETIQUETA).put(cifrado).put(etiqueta).array();
            return new String(descifrador.doFinal(sellado), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("No se pudo descifrar: la llave (CIFRADO_LLAVE) no es la misma con que se cifro", e);
        }
    }
}
