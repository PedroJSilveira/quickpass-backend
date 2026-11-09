package br.com.quickpass.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Relógio injetável. Toda regra com prazo usa este serviço, o que permite
 * "avançar o tempo" na demonstração/testes (POST /api/admin/relogio/avancar).
 */
@Component
public class ClockService {
    public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private long offsetMs = 0;

    public Instant now() {
        return Instant.now().plusMillis(offsetMs);
    }

    public LocalDate hoje() {
        return now().atZone(ZONA).toLocalDate();
    }

    public Instant maisMinutos(long min) {
        return now().plusSeconds(min * 60);
    }

    public Instant maisHoras(long horas) {
        return maisMinutos(horas * 60);
    }

    public void avancar(long ms) {
        offsetMs += ms;
    }

    public long offsetMs() {
        return offsetMs;
    }

    public boolean passou(Instant limite) {
        return limite != null && !now().isBefore(limite);
    }
}
