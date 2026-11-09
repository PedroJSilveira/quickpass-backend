package br.com.quickpass.domain;

import java.time.Instant;
import java.util.List;

public class Disputa {
    public String id;
    public String reservaId;
    public StatusDisputa status;
    public Avaria avaria;
    public Contestacao contestacao;
    public Instant contestarAte;
    public DecisaoDisputa decisao;

    public record Avaria(String descricao, int qtdFotos, Instant informadaEm, double valorEstimado, List<Arquivo> fotos) {
    }

    public record Contestacao(String texto, int qtdEvidencias, Instant enviadaEm, List<Arquivo> evidencias) {
    }
}
