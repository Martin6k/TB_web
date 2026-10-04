package com.tbridge.debt.model;

import com.tbridge.debt.config.Cifrado;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Un texto que se guarda cifrado y se lee en claro: la entidad nunca ve el
 * valor cifrado. No sirve para buscar por el, porque cada vez que se cifra sale
 * distinto. Hibernate lo crea por Spring, que le pasa el {@link Cifrado}.
 */
@Converter
public class SecretoCifrado implements AttributeConverter<String, String> {

    private final Cifrado cifrado;

    public SecretoCifrado(Cifrado cifrado) {
        this.cifrado = cifrado;
    }

    @Override
    public String convertToDatabaseColumn(String valor) {
        return cifrado.cifrar(valor);
    }

    @Override
    public String convertToEntityAttribute(String valor) {
        return cifrado.descifrar(valor);
    }
}
