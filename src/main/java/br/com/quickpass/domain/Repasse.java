package br.com.quickpass.domain;

import java.time.LocalDate;

public class Repasse {
    public String reservaId;
    public String veiculoId;
    public String proprietarioId;
    public String veiculoTitulo;
    public String periodo;
    public double bruto;
    public double taxa;
    public double liquido;
    public StatusRepasse status;
    public LocalDate pagoEm;
}
