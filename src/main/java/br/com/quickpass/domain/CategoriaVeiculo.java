package br.com.quickpass.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Serializado pelo rótulo exibido na UI (ex.: "Sedã"). */
public enum CategoriaVeiculo {
    HATCH("Hatch"), SEDA("Sedã"), SUV("SUV"), HIBRIDO("Híbrido"), ELETRICO("Elétrico");

    private final String rotulo;

    CategoriaVeiculo(String rotulo) {
        this.rotulo = rotulo;
    }

    @JsonValue
    public String rotulo() {
        return rotulo;
    }

    @JsonCreator
    public static CategoriaVeiculo deRotulo(String valor) {
        for (var v : values()) {
            if (v.rotulo.equalsIgnoreCase(valor) || v.name().equalsIgnoreCase(valor)) return v;
        }
        throw new IllegalArgumentException("Valor inválido para CategoriaVeiculo: " + valor);
    }
}
