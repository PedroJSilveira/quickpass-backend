package br.com.quickpass.domain;

import java.time.Instant;
import java.time.LocalDate;

/** Busca salva do locatário (RF14): localização aproximada, nunca exata. */
public class BuscaSalva {
    public String id;
    public String renterId;
    public String regiao;
    public double raioKm;
    public LocalDate inicio;
    public LocalDate fim;
    public String categoria;
    public Double precoMax;
    public boolean alertasAtivos;
    public boolean pausada;
    public Instant criadaEm;
}
