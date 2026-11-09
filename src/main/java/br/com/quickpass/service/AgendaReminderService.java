package br.com.quickpass.service;

import br.com.quickpass.config.*;
import br.com.quickpass.domain.StatusVeiculo;
import br.com.quickpass.domain.TipoNotificacao;
import br.com.quickpass.domain.Veiculo;
import org.springframework.stereotype.Service;

import static br.com.quickpass.common.Util.props;

/**
 * Régua de lembrete de revisão de agenda / anti-churn (RF07). Só para veículo ativo realmente sem
 * oferta há DIAS_SEM_OFERTA dias consecutivos nos próximos HORIZONTE dias — "não editou a agenda"
 * NÃO é critério. Exige consentimento, respeita cadência semanal/total e encerra ao liberar datas.
 */
@Service
public class AgendaReminderService {
    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;
    private final DemandService demanda;
    private final NotificationService notificacoes;
    private final SupplyHealthService supply;

    public AgendaReminderService(Store store, AppConfigService config, ClockService clock, DemandService demanda,
                                 NotificationService notificacoes, SupplyHealthService supply) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.demanda = demanda;
        this.notificacoes = notificacoes;
        this.supply = supply;
    }

    /** O proprietário recusou/descadastrou: a régua dele encerra de vez. */
    public void recusar(String ownerId) {
        store.recusaramLembrete.add(ownerId);
    }

    /** Rotina de auditoria de oferta (BDD 9.2). Retorna quantos lembretes enviou. */
    public int executar() {
        supply.varrer();
        int enviados = 0;
        for (Veiculo v : store.veiculos.values()) {
            if (v.status != StatusVeiculo.APROVADO) continue;
            // Régua encerrada ao liberar datas: com oferta aberta, o estado é descartado.
            if (supply.temOferta(v.id)) {
                store.reguas.remove(v.id);
                continue;
            }
            if (!supply.desengajado(v.id) || store.recusaramLembrete.contains(v.proprietarioId)) continue;
            var estado = store.reguas.getOrDefault(v.id, new Store.EstadoRegua(0, null));
            if (estado.enviados() >= config.getInt(Parametro.MAX_AVISOS_TOTAL)) continue;
            if (notificacoes.enviadasNaSemana(v.proprietarioId, TipoNotificacao.REVISAO_AGENDA) >= config.getInt(Parametro.MAX_AVISOS_SEMANA)) continue;
            var d = demanda.demanda(v.local);
            long dias = supply.diasSemOferta(v.id);
            int janela = config.getInt(Parametro.JANELA_DEMANDA_DIAS);
            // Estimativa de demanda só com amostra suficiente; sempre como dado, nunca como perda.
            String texto = d.suficiente()
                    ? "Seu %s está sem datas disponíveis há %d dias. Houve %d buscas compatíveis em %s nos últimos %d dias — que tal revisar sua agenda?"
                    .formatted(v.titulo, dias, d.buscas(), v.local, janela)
                    : "Seu %s está sem datas disponíveis há %d dias. Que tal revisar sua agenda?".formatted(v.titulo, dias);
            var n = notificacoes.enviar(v.proprietarioId, TipoNotificacao.REVISAO_AGENDA, "Revise sua agenda", texto,
                    "/proprietario/veiculos/" + v.id + "/disponibilidade?origem=notificacao",
                    props("vehicle_id", v.id, "dias_sem_oferta", dias, "horizonte_dias", config.getInt(Parametro.HORIZONTE_DISPONIBILIDADE_DIAS),
                            "demanda", d.suficiente() ? props("buscas", d.buscas()) : "dados insuficientes"));
            if (n != null) {
                store.reguas.put(v.id, new Store.EstadoRegua(estado.enviados() + 1, clock.now()));
                enviados++;
            }
        }
        return enviados;
    }
}
