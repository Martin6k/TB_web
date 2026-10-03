package com.tbridge.debt.config;

import com.tbridge.debt.model.SecretoCifrado;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El secreto de las suscripciones, cifrado en la base y en claro para quien firma. */
class CifradoTest {

    private final Cifrado cifrado = new Cifrado("llave-de-prueba");

    @Test
    void cifraYDescifraDeVueltaSinDejarElValorALaVista() {
        String guardado = cifrado.cifrar("whsec_de_prueba");
        assertThat(guardado).startsWith("enc:v1:").doesNotContain("whsec_de_prueba");
        assertThat(cifrado.descifrar(guardado)).isEqualTo("whsec_de_prueba");
        assertThat(cifrado.cifrar("whsec_de_prueba")).as("cada vez con su propio IV").isNotEqualTo(guardado);
    }

    @Test
    void loVacioLoYaCifradoYLoDeAntesPasanIgual() {
        assertThat(cifrado.cifrar(null)).isNull();
        assertThat(cifrado.cifrar("")).isEmpty();
        String una = cifrado.cifrar("x");
        assertThat(cifrado.cifrar(una)).as("no se cifra dos veces").isEqualTo(una);
        assertThat(cifrado.descifrar("en-claro-de-antes")).isEqualTo("en-claro-de-antes");
    }

    @Test
    void conOtraLlaveOConUnByteCambiadoNoSeDescifra() {
        String guardado = cifrado.cifrar("whsec_de_prueba");
        assertThatThrownBy(() -> new Cifrado("otra-llave").descifrar(guardado))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CIFRADO_LLAVE");

        byte[] bytes = Base64.getDecoder().decode(guardado.substring(Cifrado.PREFIJO.length()));
        bytes[bytes.length - 1] ^= 1;
        String alterado = Cifrado.PREFIJO + Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> cifrado.descifrar(alterado)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elConvertidorGuardaCifradoYEntregaEnClaro() {
        SecretoCifrado convertidor = new SecretoCifrado(cifrado);
        String columna = convertidor.convertToDatabaseColumn("whsec_de_prueba");
        assertThat(columna).startsWith("enc:v1:");
        assertThat(convertidor.convertToEntityAttribute(columna)).isEqualTo("whsec_de_prueba");
    }

    @Test
    void alArrancarSeCifranLosQueQuedaronEnClaro() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("enc:v1:%")))
                .thenReturn(List.of(Map.of("id", 7L, "secret", "whsec_en_claro")));

        new CifrarSecretosGuardados(jdbc, cifrado).run(null);

        verify(jdbc).update(eq("UPDATE subscriptions SET secret = ? WHERE id = ?"),
                argThat((String s) -> "whsec_en_claro".equals(cifrado.descifrar(s)) && s.startsWith("enc:v1:")),
                eq(7L));
    }

    @Test
    void siNoQuedaNadaEnClaroNoSeEscribe() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("enc:v1:%"))).thenReturn(List.of());

        new CifrarSecretosGuardados(jdbc, cifrado).run(null);

        verify(jdbc, never()).update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
    }
}
