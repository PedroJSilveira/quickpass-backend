package br.com.quickpass.service;

import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.BuscaRegistrada;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Sinais de demanda regionais (RF05/RF11/RF16) a partir de buscas reais e
 * solicitações recebidas. Região = bairro (localização aproximada, RNF02).
 */
@Service
public class DemandService {
    public record DemandaRegional(String regiao, long buscas, long solicitacoes, long amostra, boolean suficiente) {
    }

    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;

    public DemandService(Store store, AppConfigService config, ClockService clock) {
        this.store = store;
        this.config = config;
        this.clock = clock;
    }

    public void registrarBusca(String regiao, String categoria, LocalDate inicio, LocalDate fim) {
        store.buscasRegistradas.add(new BuscaRegistrada(regiao, categoria, inicio, fim, clock.now()));
    }

    private Instant janelaInicio() {
        return clock.now().minusSeconds(config.getInt(Parametro.JANELA_DEMANDA_DIAS) * 24L * 3600);
    }

    /** Buscas compatíveis (região/categoria) na janela de demanda. */
    public long buscasCompativeis(String regiao, String categoria) {
        Instant ini = janelaInicio();
        return store.buscasRegistradas.stream()
                .filter(b -> b.regiao().equals(regiao))
                .filter(b -> categoria == null || "Todos".equals(b.categoria()) || b.categoria().equalsIgnoreCase(categoria))
                .filter(b -> !b.em().isBefore(ini))
                .count();
    }

    public long solicitacoesNaRegiao(String regiao) {
        Instant ini = janelaInicio();
        return store.reservas.values().stream()
                .filter(r -> {
                    var v = store.veiculos.get(r.veiculoId);
                    return v != null && regiao.equals(v.local);
                })
                .filter(r -> !r.criadaEm.isBefore(ini))
                .count();
    }

    public DemandaRegional demanda(String regiao) {
        long buscas = buscasCompativeis(regiao, null);
        long sol = solicitacoesNaRegiao(regiao);
        long amostra = buscas + sol;
        return new DemandaRegional(regiao, buscas, sol, amostra, amostra >= config.getInt(Parametro.AMOSTRA_MINIMA_REGIAO));
    }
}
