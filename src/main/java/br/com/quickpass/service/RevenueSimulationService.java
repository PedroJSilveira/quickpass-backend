package br.com.quickpass.service;

import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static br.com.quickpass.common.Datas.diasEntre;
import static br.com.quickpass.common.Util.props;
import static br.com.quickpass.common.Util.r2;

/**
 * Simulação de Receita Potencial (RF05): projeção com premissas visíveis, mostrada SEPARADAMENTE
 * de solicitações, locações concluídas e valor efetivamente recebido. Sem amostra mínima regional:
 * "dados insuficientes". receita_potencial = preço_médio_diária_região × dias_indisponíveis × taxa_ocupação_região.
 */
@Service
public class RevenueSimulationService {
    private static final int JANELA_DIAS = 28;

    public record Premissas(double precoMedioDiaria, long diasIndisponiveis, double taxaOcupacao, long amostra, int janelaDias) {
    }

    public record SimulacaoReceita(String regiao, boolean suficiente, Premissas premissas, Double projecao,
                                   long solicitacoesRecebidas, long locacoesConcluidas, double valorRecebido) {
    }

    private final Store store;
    private final AgendaService agenda;
    private final DemandService demanda;
    private final PayoutService payout;
    private final ClockService clock;
    private final EventBus bus;

    public RevenueSimulationService(Store store, AgendaService agenda, DemandService demanda, PayoutService payout, ClockService clock, EventBus bus) {
        this.store = store;
        this.agenda = agenda;
        this.demanda = demanda;
        this.payout = payout;
        this.clock = clock;
        this.bus = bus;
    }

    public SimulacaoReceita simular(Usuario dono, String veiculoId) {
        Veiculo v = agenda.veiculoDoUsuario(dono, veiculoId);
        String regiao = v.local;
        List<Veiculo> daRegiao = store.veiculos.values().stream().filter(x -> x.local.equals(regiao) && x.status == StatusVeiculo.APROVADO).toList();
        double precoMedio = daRegiao.stream().mapToDouble(x -> x.diaria).average().orElse(v.diaria);
        LocalDate hoje = clock.hoje();
        Disponibilidade ag = agenda.agendaConfigurada(veiculoId);
        long indisponiveis = ag == null ? 0 : diasEntre(hoje, hoje.plusDays(JANELA_DIAS)).stream().filter(d -> !Boolean.TRUE.equals(ag.dias.get(d))).count();

        // Taxa de ocupação observada: diárias efetivamente reservadas na região / (veículos × janela).
        LocalDate ini = hoje.minusDays(JANELA_DIAS / 2), fim = hoje.plusDays(JANELA_DIAS / 2);
        Set<String> ids = new java.util.HashSet<>();
        daRegiao.forEach(x -> ids.add(x.id));
        Set<StatusReserva> pagas = EnumSet.of(StatusReserva.CONFIRMADA, StatusReserva.EM_ANDAMENTO, StatusReserva.EM_DISPUTA, StatusReserva.CONCLUIDA);
        long diasReservados = store.reservas.values().stream().filter(r -> ids.contains(r.veiculoId) && pagas.contains(r.status))
                .mapToLong(r -> diasEntre(r.inicio.isBefore(ini) ? ini : r.inicio, r.fim.isAfter(fim) ? fim : r.fim).size()).sum();
        double ocupacao = Math.min(1, (double) diasReservados / (Math.max(1, daRegiao.size()) * JANELA_DIAS));

        var d = demanda.demanda(regiao);
        var doVeiculo = store.reservas.values().stream().filter(r -> r.veiculoId.equals(veiculoId)).toList();
        var concluidas = doVeiculo.stream().filter(r -> r.status == StatusReserva.CONCLUIDA).toList();
        double recebido = concluidas.stream().mapToDouble(r -> {
            Repasse rep = payout.daReserva(r.id);
            return rep != null && rep.status == StatusRepasse.PAGO ? rep.liquido : 0;
        }).sum();
        var res = new SimulacaoReceita(regiao, d.suficiente(), new Premissas(r2(precoMedio), indisponiveis, ocupacao, d.amostra(), JANELA_DIAS),
                d.suficiente() ? r2(precoMedio * indisponiveis * ocupacao) : null,
                doVeiculo.stream().filter(r -> r.status != StatusReserva.AGUARDANDO_KYC).count(), concluidas.size(), r2(recebido));
        bus.emitir("simulacao_receita_visualizada", props("user_id", dono.id, "vehicle_id", veiculoId, "dados_suficientes", res.suficiente()));
        return res;
    }
}
