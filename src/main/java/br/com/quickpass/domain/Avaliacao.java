package br.com.quickpass.domain;

import java.time.Instant;
import java.util.List;

public class Avaliacao {
    public String reservaId;
    public String autorId;
    /** Papel de quem avaliou: define quem é o alvo (veículo/proprietário ou locatário). */
    public Papel autorPapel;
    public String alvoId;
    public Instant enviadaEm;
    /** Preenchido quando a avaliação é publicada (RF06). */
    public Instant publicadaEm;
    public int nota;
    public String texto;
    public List<String> selos;
}
