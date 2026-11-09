package br.com.quickpass.domain;

import java.time.Instant;

public class Disputa {
    public String id;
    public String reservaId;
    public StatusDisputa status;
    public Avaria avaria;
    public Contestacao contestacao;
    public Instant contestarAte;
    public DecisaoDisputa decisao;

    public record Avaria(String descricao, int qtdFotos, Instant informadaEm, double valorEstimado) {
    }

    public record Contestacao(String texto, int qtdEvidencias, Instant enviadaEm) {
    }
}
