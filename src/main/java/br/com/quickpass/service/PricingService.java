package br.com.quickpass.service;

import br.com.quickpass.domain.Protecao;
import br.com.quickpass.domain.Valores;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static br.com.quickpass.common.Util.r2;

/** Fonte única da verdade para os cálculos financeiros da plataforma. */
@Service
public class PricingService {
    /** Percentual retido pela plataforma sobre o subtotal da locação. */
    public static final double TAXA_PCT = 15;
    /** Caução pré-autorizada no cartão (não é receita; liberada após o check-out). */
    public static final double CAUCAO = 500;
    public static final double PROTECAO_BASICA_DIA = 25;
    public static final double PROTECAO_COMPLETA_DIA = 45;
    public static final double FRANQUIA_BASICA = 3000;
    public static final double FRANQUIA_COMPLETA = 800;

    public double precoProtecao(Protecao p) {
        return p == Protecao.BASICA ? PROTECAO_BASICA_DIA : PROTECAO_COMPLETA_DIA;
    }

    /** Número de diárias entre duas datas, sempre ≥ 1. */
    public int dias(LocalDate inicio, LocalDate fim) {
        long d = ChronoUnit.DAYS.between(inicio, fim);
        return d > 0 ? (int) d : 1;
    }

    /**
     * A taxa incide só sobre o subtotal da locação (não sobre a proteção); a proteção é
     * receita da plataforma; a caução não entra no total nem em receita.
     */
    public Valores calcular(double diaria, Protecao protecao, LocalDate inicio, LocalDate fim) {
        int dias = dias(inicio, fim);
        double subtotal = r2(diaria * dias);
        double protecaoValor = r2(precoProtecao(protecao) * dias);
        double taxa = r2(subtotal * TAXA_PCT / 100);
        return new Valores(diaria, dias, subtotal, protecaoValor, taxa, r2(subtotal + protecaoValor), r2(subtotal - taxa), CAUCAO);
    }

    public String formatarPeriodo(LocalDate inicio, LocalDate fim) {
        int d = dias(inicio, fim);
        return "%s – %s · %d %s".formatted(curta(inicio), curta(fim), d, d > 1 ? "dias" : "dia");
    }

    private String curta(LocalDate d) {
        String[] meses = {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};
        return "%02d %s".formatted(d.getDayOfMonth(), meses[d.getMonthValue() - 1]);
    }
}
