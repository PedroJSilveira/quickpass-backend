package br.com.quickpass.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;

public final class Datas {
    private Datas() {
    }

    /** Dias de {@code inicio} (inclusive) até {@code fim} (exclusivo); cada dia é uma diária. */
    public static List<LocalDate> diasEntre(LocalDate inicio, LocalDate fim) {
        List<LocalDate> dias = new ArrayList<>();
        for (LocalDate d = inicio; d.isBefore(fim); d = d.plusDays(1)) dias.add(d);
        return dias;
    }

    /** Intervalos [aIni, aFim) e [bIni, bFim) se sobrepõem? Devolução e retirada no mesmo dia não conflitam. */
    public static boolean sobrepoe(LocalDate aIni, LocalDate aFim, LocalDate bIni, LocalDate bFim) {
        return aIni.isBefore(bFim) && bIni.isBefore(aFim);
    }

    /** Semana ISO no formato "YYYY-Www". */
    public static String semanaIso(Instant i) {
        LocalDate d = i.atZone(ZoneOffset.UTC).toLocalDate();
        return "%d-W%02d".formatted(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
