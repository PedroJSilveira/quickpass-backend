package br.com.quickpass.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

public class Disponibilidade {
    public String veiculoId;
    /** Dia → liberado. */
    public Map<LocalDate, Boolean> dias = new TreeMap<>();
    public boolean aceiteAutomatico;

    public Disponibilidade() {
    }

    public Disponibilidade(String veiculoId) {
        this.veiculoId = veiculoId;
    }
}
