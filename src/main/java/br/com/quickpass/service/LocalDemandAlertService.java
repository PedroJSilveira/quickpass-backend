package br.com.quickpass.service;

import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.StatusVeiculo;
import br.com.quickpass.domain.TipoNotificacao;
import br.com.quickpass.domain.Veiculo;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

import static br.com.quickpass.common.Util.props;

/**
 * Notificação de demanda local (RF16): "há procura na sua região" somente com evidência — volume
 * mínimo de buscas compatíveis + solicitações na janela. Abaixo do mínimo não envia nada; sem
 * urgência artificial; registra o dado que justificou o envio.
 */
@Service
public class LocalDemandAlertService {
    private final Store store;
    private final AppConfigService config;
    private final DemandService demanda;
    private final NotificationService notificacoes;

    public LocalDemandAlertService(Store store, AppConfigService config, DemandService demanda, NotificationService notificacoes) {
        this.store = store;
        this.config = config;
        this.demanda = demanda;
        this.notificacoes = notificacoes;
    }

    /** Rotina de demanda local (BDD 9.5). Retorna quantos alertas enviou. */
    public int executar() {
        int janela = config.getInt(Parametro.JANELA_DEMANDA_DIAS);
        int minimo = config.getInt(Parametro.MIN_BUSCAS_DEMANDA);
        Set<String> avisados = new HashSet<>();
        int enviados = 0;
        for (Veiculo v : store.veiculos.values()) {
            if (v.status != StatusVeiculo.APROVADO || avisados.contains(v.proprietarioId)) continue;
            var d = demanda.demanda(v.local);
            if (d.buscas() + d.solicitacoes() < minimo) continue; // sem evidência suficiente, nenhuma notificação
            if (notificacoes.enviadasNaSemana(v.proprietarioId, TipoNotificacao.DEMANDA_LOCAL) >= config.getInt(Parametro.MAX_AVISOS_SEMANA)) continue;
            var n = notificacoes.enviar(v.proprietarioId, TipoNotificacao.DEMANDA_LOCAL, "Procura na sua região",
                    "%d buscas e %d solicitações em %s nos últimos %d dias.".formatted(d.buscas(), d.solicitacoes(), v.local, janela),
                    "/proprietario/veiculos/" + v.id + "/disponibilidade?origem=notificacao",
                    props("regiao", v.local, "buscas", d.buscas(), "solicitacoes", d.solicitacoes(), "janela_dias", janela, "minimo", minimo));
            if (n != null) {
                avisados.add(v.proprietarioId);
                enviados++;
            }
        }
        return enviados;
    }
}
