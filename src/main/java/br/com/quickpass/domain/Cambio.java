package br.com.quickpass.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Serializado pelo rótulo exibido na UI (ex.: "Sedã"). */
public enum Cambio {
    MANUAL("Manual"), AUTOMATICO("Automático");

    private final String rotulo;

    Cambio(String rotulo) {
        this.rotulo = rotulo;
    }

    @JsonValue
    public String rotulo() {
        return rotulo;
    }

    @JsonCreator
    public static Cambio deRotulo(String valor) {
        for (var v : values()) {
            if (v.rotulo.equalsIgnoreCase(valor) || v.name().equalsIgnoreCase(valor)) return v;
        }
        throw new IllegalArgumentException("Valor inválido para Cambio: " + valor);
    }
}
