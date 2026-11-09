package br.com.quickpass.domain;

import java.time.Instant;

public class Caucao {
    public String reservaId;
    public double valor;
    public StatusCaucao status;
    public Instant preAutorizadaEm;
    /** Validade da pré-autorização (cobre locação + prazo de liberação + contestação). */
    public Instant validaAte;
    public double valorCapturado;
    public Instant liberarEm;
}
