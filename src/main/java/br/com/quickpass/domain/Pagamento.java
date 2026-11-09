package br.com.quickpass.domain;

import java.time.Instant;

public class Pagamento {
    public String reservaId;
    public double valorDiaria;
    public double taxaPlataforma;
    public double desconto;
    public double total;
    public double estornado;
    public StatusPagamento status;
    public Instant pagoEm;
}
