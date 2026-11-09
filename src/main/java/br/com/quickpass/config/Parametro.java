package br.com.quickpass.config;

/**
 * Parâmetros configuráveis (QUICKPASS_REQUISITOS.md §11, RNF04). Nenhuma regra
 * de negócio usa número literal: leia via {@link AppConfigService}. Os marcados
 * como provisórios estão "a definir" no documento e devem ser validados (§14).
 */
public enum Parametro {
    HOLD_KYC_MINUTOS(15, false),
    PRAZO_APROVACAO_RESERVA_HORAS(24, false),
    PRAZO_PAGAMENTO_HORAS(24, true),
    JANELA_CHECKIN_MINUTOS(120, true),
    PRAZO_LIBERACAO_CAUCAO_HORAS(24, false),
    PRAZO_CONTESTACAO_HORAS(48, true),
    PRAZO_AVALIACAO_HORAS(72, false),
    DIAS_SEM_OFERTA(15, false),
    HORIZONTE_DISPONIBILIDADE_DIAS(14, false),
    MAX_AVISOS_SEMANA(1, false),
    MAX_AVISOS_TOTAL(4, true),
    JANELA_DEMANDA_DIAS(7, false),
    RAIO_DEMANDA_KM(5, true),
    MIN_BUSCAS_DEMANDA(10, true),
    AMOSTRA_MINIMA_REGIAO(20, true),
    JANELA_DESFAZER_AGENDA_MIN(10, true),
    JANELA_ATRIBUICAO_NOTIFICACAO_MIN(30, true),
    LIMITE_NOTIFICACOES_GLOBAL_SEMANA(3, true),
    REFERRAL_BONUS_VALOR(0, true),
    REFERRAL_TETO_PERIODO(0, true),
    META_GUARDRAIL_DISPUTA(3, false),
    META_GUARDRAIL_CHURN_SILENCIOSO(15, false),
    PESO_REPUTACAO_RANKING(0.3, true),
    PESO_COMPLETUDE_RANKING(0.2, true),
    CANCELAMENTO_REEMBOLSO_TOTAL_HORAS(48, true),
    CANCELAMENTO_REEMBOLSO_PARCIAL_PCT(50, true),
    NOTA_MINIMA_APROVACAO_AUTOMATICA(4.5, true),
    EXP_RF09_DURACAO_DIAS(60, true),
    EXP_RF09_ORCAMENTO(5000, true),
    EXP_RF09_PCT_TRATAMENTO(50, true),
    EXP_RF09_ATIVACAO_DIAS(30, true),
    FOTOS_VISTORIA_OBRIGATORIAS(7, false),
    FOTOS_VEICULO_MINIMAS(6, true);

    public final double padrao;
    public final boolean provisorio;

    Parametro(double padrao, boolean provisorio) {
        this.padrao = padrao;
        this.provisorio = provisorio;
    }
}
