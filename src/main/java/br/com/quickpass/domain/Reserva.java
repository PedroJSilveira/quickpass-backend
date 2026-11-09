package br.com.quickpass.domain;

import java.time.Instant;
import java.time.LocalDate;

public class Reserva {
    public String id; // "QP-2301"
    public String veiculoId;
    public String locatarioId;
    public LocalDate inicio;
    public LocalDate fim;
    public Protecao protecao;
    public StatusReserva status;
    /** Congelado no momento da solicitação. */
    public Valores valores;
    public boolean avaliada;
    public Instant concluidaEm;
    public String finalidade;
    /** Origem da solicitação (ex.: "busca", "radar"). */
    public String origem = "busca";
    public Instant criadaEm;
    /** Hold na agenda: vale enquanto status ∈ {AGUARDANDO_KYC, SOLICITADA, APROVADA}. */
    public Instant holdExpiraEm;
    /** Prazo para o proprietário responder (RF12). */
    public Instant aprovacaoExpiraEm;
    public Instant checkoutEm;
}
