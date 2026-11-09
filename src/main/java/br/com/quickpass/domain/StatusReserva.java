package br.com.quickpass.domain;

import java.util.EnumSet;
import java.util.Set;

/** Estados da reserva (QUICKPASS_REQUISITOS.md §4.2) e suas transições válidas. */
public enum StatusReserva {
    AGUARDANDO_KYC, SOLICITADA, APROVADA, CONFIRMADA, EM_ANDAMENTO, EM_DISPUTA,
    CONCLUIDA, REJEITADA, EXPIRADA, CANCELADA, FALHA_PAGAMENTO;

    public Set<StatusReserva> proximos() {
        return switch (this) {
            case AGUARDANDO_KYC -> EnumSet.of(SOLICITADA, EXPIRADA, CANCELADA);
            case SOLICITADA -> EnumSet.of(APROVADA, REJEITADA, EXPIRADA, CANCELADA);
            case APROVADA -> EnumSet.of(CONFIRMADA, FALHA_PAGAMENTO, EXPIRADA, CANCELADA);
            case CONFIRMADA -> EnumSet.of(EM_ANDAMENTO, CANCELADA);
            case EM_ANDAMENTO -> EnumSet.of(CONCLUIDA, EM_DISPUTA);
            case EM_DISPUTA -> EnumSet.of(CONCLUIDA);
            default -> EnumSet.noneOf(StatusReserva.class);
        };
    }

    public boolean podeIrPara(StatusReserva destino) {
        return proximos().contains(destino);
    }

    /** Estados que ocupam a agenda do veículo. */
    public static final Set<StatusReserva> OCUPAM_AGENDA =
            EnumSet.of(AGUARDANDO_KYC, SOLICITADA, APROVADA, CONFIRMADA, EM_ANDAMENTO);
    /** Holds com prazo de validade. */
    public static final Set<StatusReserva> HOLDS = EnumSet.of(AGUARDANDO_KYC, SOLICITADA, APROVADA);
    /** Estados em que o pagamento já foi efetivado. */
    public static final Set<StatusReserva> PAGAS = EnumSet.of(CONFIRMADA, EM_ANDAMENTO, EM_DISPUTA, CONCLUIDA);
}
