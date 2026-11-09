package br.com.quickpass.service;

import br.com.quickpass.config.*;
import br.com.quickpass.domain.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.function.DoubleSupplier;

import static br.com.quickpass.common.Util.props;
import static br.com.quickpass.common.Util.r2;

/**
 * RF09 — isenção da taxa de intermediação na PRIMEIRA locação de novos proprietários, tratada
 * como experimento: grupos aleatórios e imutáveis, intervalo, orçamento máximo, indicador de
 * sucesso (ativação) e critérios de parada (prazo, orçamento ou guardrail de disputas violado).
 */
@Service
public class ExperimentService {
    public record Linha(GrupoExperimento grupo, int atribuidos, int ativados, double taxaAtivacao) {
    }

    public record Resultado(List<Linha> linhas, double diferencaPontos) {
    }

    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;
    private final EventBus bus;
    private final MetricsService metrics;
    /** Fonte de aleatoriedade (substituível em testes). */
    public DoubleSupplier sorteio = Math::random;

    public ExperimentService(Store store, AppConfigService config, ClockService clock, EventBus bus, MetricsService metrics) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.bus = bus;
        this.metrics = metrics;
    }

    public EstadoExperimento estado() {
        if (store.experimento == null) {
            EstadoExperimento e = new EstadoExperimento();
            e.hipotese = "A isenção da taxa na 1ª locação aumenta a ativação de novos proprietários (1ª locação concluída em até N dias do cadastro).";
            e.inicio = clock.now();
            e.fim = clock.now().plus(Duration.ofDays(config.getInt(Parametro.EXP_RF09_DURACAO_DIAS)));
            e.orcamento = config.get(Parametro.EXP_RF09_ORCAMENTO);
            store.experimento = e;
        }
        return store.experimento;
    }

    /** Verifica os critérios de parada; uma vez encerrado, não reabre. */
    public boolean ativo() {
        EstadoExperimento e = estado();
        if (e.encerradoPor != null) return false;
        if (clock.passou(e.fim)) return encerrar(MotivoEncerramento.PRAZO);
        if (e.gasto >= e.orcamento) return encerrar(MotivoEncerramento.ORCAMENTO);
        if (metrics.guardrailDisputa(null).violado()) return encerrar(MotivoEncerramento.GUARDRAIL);
        return true;
    }

    private boolean encerrar(MotivoEncerramento m) {
        estado().encerradoPor = m;
        return false;
    }

    /** Elegível: proprietário com KYC aprovado e sem locação concluída anterior. */
    public boolean elegivel(String ownerId, boolean temConcluidaAnterior) {
        Usuario dono = store.usuarios.get(ownerId);
        return dono != null && dono.statusKyc == StatusKyc.APROVADO && !temConcluidaAnterior;
    }

    /** Sorteio persistido e imutável: o grupo nunca muda depois de atribuído. */
    public GrupoExperimento atribuir(String ownerId) {
        var existente = store.gruposExperimento.get(ownerId);
        if (existente != null) return existente.grupo();
        GrupoExperimento g = sorteio.getAsDouble() * 100 < config.get(Parametro.EXP_RF09_PCT_TRATAMENTO) ? GrupoExperimento.TRATAMENTO : GrupoExperimento.CONTROLE;
        store.gruposExperimento.put(ownerId, new Store.GrupoAtribuido(g, clock.now()));
        bus.emitir("experimento_atribuido", props("user_id", ownerId, "experimento_id", "RF09", "grupo", g.name().toLowerCase()));
        return g;
    }

    public GrupoExperimento grupoDe(String ownerId) {
        var g = store.gruposExperimento.get(ownerId);
        return g == null ? null : g.grupo();
    }

    /** Desconto de 100% da taxa na 1ª transação válida do grupo tratamento; 0 se não se aplica. Respeita o orçamento. */
    public double avaliarDesconto(String ownerId, double taxa, boolean temConcluidaAnterior, String reservaId) {
        if (!ativo() || !elegivel(ownerId, temConcluidaAnterior)) return 0;
        if (atribuir(ownerId) != GrupoExperimento.TRATAMENTO || store.comDesconto.contains(ownerId)) return 0;
        EstadoExperimento e = estado();
        if (e.gasto + taxa > e.orcamento) {
            encerrar(MotivoEncerramento.ORCAMENTO);
            return 0;
        }
        store.comDesconto.add(ownerId);
        e.gasto = r2(e.gasto + taxa);
        bus.emitir("desconto_aplicado", props("user_id", ownerId, "experimento_id", "RF09", "reservation_id", reservaId, "valor", taxa));
        if (e.gasto >= e.orcamento) encerrar(MotivoEncerramento.ORCAMENTO);
        return taxa;
    }

    /** Indicador de sucesso: taxa de ativação (1ª locação concluída em até N dias da atribuição) por grupo. */
    public Resultado resultado() {
        long janelaMs = config.getInt(Parametro.EXP_RF09_ATIVACAO_DIAS) * 86_400_000L;
        List<Linha> linhas = new ArrayList<>();
        for (GrupoExperimento g : GrupoExperimento.values()) {
            var donos = store.gruposExperimento.entrySet().stream().filter(e -> e.getValue().grupo() == g).toList();
            int ativados = (int) donos.stream().filter(e -> store.reservas.values().stream().anyMatch(r -> {
                if (r.status != StatusReserva.CONCLUIDA) return false;
                Veiculo v = store.veiculos.get(r.veiculoId);
                if (v == null || !v.proprietarioId.equals(e.getKey())) return false;
                long t = (r.concluidaEm != null ? r.concluidaEm : r.criadaEm).toEpochMilli();
                long em = e.getValue().em().toEpochMilli();
                return t >= em && t - em <= janelaMs;
            })).count();
            linhas.add(new Linha(g, donos.size(), ativados, donos.isEmpty() ? 0 : (double) ativados / donos.size() * 100));
        }
        return new Resultado(linhas, linhas.get(0).taxaAtivacao() - linhas.get(1).taxaAtivacao());
    }
}
