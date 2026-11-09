package br.com.quickpass.domain;

import java.time.Instant;

public class EstadoExperimento {
    public String id = "RF09";
    public String hipotese;
    public Instant inicio;
    public Instant fim;
    public double orcamento;
    public double gasto;
    public MotivoEncerramento encerradoPor;
}
