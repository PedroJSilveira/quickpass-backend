package br.com.quickpass.service;

import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.StatusVeiculo;
import br.com.quickpass.domain.Veiculo;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static br.com.quickpass.common.Datas.diasEntre;

/**
 * Saúde da oferta: há quantos dias cada veículo ativo está SEM nenhuma data disponível nos
 * próximos HORIZONTE_DISPONIBILIDADE_DIAS (RF07 e guardrail 2). Agenda já aberta nunca conta
 * como desengajada — "última edição da agenda" não é critério.
 */
@Service
public class SupplyHealthService {
    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;
    private final AgendaService agenda;

    public SupplyHealthService(Store store, AppConfigService config, ClockService clock, AgendaService agenda) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.agenda = agenda;
    }

    /** Há alguma data disponível nos próximos N dias? Agenda nunca configurada conta como aberta. */
    public boolean temOferta(String veiculoId) {
        LocalDate hoje = clock.hoje();
        List<LocalDate> dias = diasEntre(hoje, hoje.plusDays(config.getInt(Parametro.HORIZONTE_DISPONIBILIDADE_DIAS) + 1L));
        var d = agenda.agendaConfigurada(veiculoId);
        if (d == null) return true;
        return dias.stream().anyMatch(x -> Boolean.TRUE.equals(d.dias.get(x))) && agenda.algumDiaLivreDeReserva(veiculoId, dias);
    }

    /** Atualiza o relógio de "sem oferta" de cada veículo ativo. */
    public void varrer() {
        Instant agora = clock.now();
        for (Veiculo v : store.veiculos.values()) {
            if (v.status != StatusVeiculo.APROVADO) {
                store.semOfertaDesde.remove(v.id);
            } else if (temOferta(v.id)) {
                store.semOfertaDesde.remove(v.id);
            } else {
                store.semOfertaDesde.putIfAbsent(v.id, agora);
            }
        }
    }

    public long diasSemOferta(String veiculoId) {
        Instant desde = store.semOfertaDesde.get(veiculoId);
        return desde == null ? 0 : Duration.between(desde, clock.now()).toDays();
    }

    /** Desengajado: ativo e há ≥ DIAS_SEM_OFERTA dias sem data disponível. */
    public boolean desengajado(String veiculoId) {
        return !temOferta(veiculoId) && diasSemOferta(veiculoId) >= config.getInt(Parametro.DIAS_SEM_OFERTA);
    }

    public record Churn(long numerador, long denominador, double pct) {
    }

    /** Guardrail 2: proprietários com veículo ativo em churn silencioso / proprietários com veículo ativo. */
    public Churn guardrailChurn() {
        Map<String, Boolean> porDono = new HashMap<>();
        for (Veiculo v : store.veiculos.values()) {
            if (v.status != StatusVeiculo.APROVADO) continue;
            porDono.merge(v.proprietarioId, desengajado(v.id), Boolean::logicalOr);
        }
        long den = porDono.size();
        long num = porDono.values().stream().filter(b -> b).count();
        return new Churn(num, den, den == 0 ? 0 : (double) num / den * 100);
    }
}
