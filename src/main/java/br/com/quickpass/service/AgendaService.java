package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.*;
import br.com.quickpass.web.Dtos.DisponibilidadeReq;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

import static br.com.quickpass.common.Datas.diasEntre;
import static br.com.quickpass.common.Datas.sobrepoe;
import static br.com.quickpass.common.Util.props;

/**
 * Agenda do proprietário: disponibilidade por veículo, "liberar com um toque" (RF11),
 * preferências de anúncio (RF17) e a checagem de conflito com reservas/holds.
 */
@Service
public class AgendaService {
    private static final Set<DayOfWeek> FIM_DE_SEMANA = EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final Store store;
    private final ClockService clock;
    private final AppConfigService config;
    private final EventBus bus;
    private final UsuarioService usuarios;
    private final DemandService demanda;

    public AgendaService(Store store, ClockService clock, AppConfigService config, EventBus bus, UsuarioService usuarios, DemandService demanda) {
        this.store = store;
        this.clock = clock;
        this.config = config;
        this.bus = bus;
        this.usuarios = usuarios;
        this.demanda = demanda;
    }

    // ───────────── conflito de agenda ─────────────

    /** Há reserva/hold ativo que ocupa a agenda do veículo em [inicio, fim)? */
    public boolean temConflito(String veiculoId, LocalDate inicio, LocalDate fim, String ignorarId) {
        return store.reservas.values().stream().anyMatch(r ->
                r.veiculoId.equals(veiculoId) && !r.id.equals(ignorarId) && ocupaAgenda(r) && sobrepoe(inicio, fim, r.inicio, r.fim));
    }

    public boolean ocupaAgenda(Reserva r) {
        if (!StatusReserva.OCUPAM_AGENDA.contains(r.status)) return false;
        // Hold vencido não ocupa mais (o job também move para EXPIRADA).
        return !(StatusReserva.HOLDS.contains(r.status) && clock.passou(r.holdExpiraEm));
    }

    public boolean algumDiaLivreDeReserva(String veiculoId, List<LocalDate> dias) {
        return dias.stream().anyMatch(d -> !temConflito(veiculoId, d, d.plusDays(1), null));
    }

    // ───────────── disponibilidade ─────────────

    public Veiculo veiculoDoUsuario(Usuario u, String veiculoId) {
        Veiculo v = store.veiculos.get(veiculoId);
        if (v == null) throw ApiException.naoEncontrado("Veículo não encontrado.");
        if (!v.proprietarioId.equals(u.id)) throw ApiException.proibido("Este veículo não é seu.");
        return v;
    }

    /** Agenda explicitamente configurada (null = nunca configurada, tratada como aberta). */
    public Disponibilidade agendaConfigurada(String veiculoId) {
        return store.disponibilidades.get(veiculoId);
    }

    /** Calendário atual; se nunca configurado, gera a base de 28 dias (liberado de qui a dom). */
    public Disponibilidade disponibilidadeDoVeiculo(String veiculoId) {
        Disponibilidade d = store.disponibilidades.get(veiculoId);
        if (d != null) return d;
        Disponibilidade base = new Disponibilidade(veiculoId);
        LocalDate hoje = clock.hoje();
        for (int i = 0; i < 28; i++) {
            LocalDate dia = hoje.plusDays(i);
            DayOfWeek dow = dia.getDayOfWeek();
            base.dias.put(dia, dow.getValue() >= DayOfWeek.THURSDAY.getValue());
        }
        return base;
    }

    /**
     * Todos os dias estão liberados? Veículo cuja agenda nunca foi configurada é tratado como
     * aberto; com agenda configurada, cada dia precisa estar explicitamente liberado.
     */
    public boolean diasLiberados(String veiculoId, List<LocalDate> dias) {
        Disponibilidade d = store.disponibilidades.get(veiculoId);
        if (d == null) return true;
        return !dias.isEmpty() && dias.stream().allMatch(x -> Boolean.TRUE.equals(d.dias.get(x)));
    }

    public boolean aceiteAutomatico(String veiculoId) {
        Disponibilidade d = store.disponibilidades.get(veiculoId);
        return d != null && d.aceiteAutomatico;
    }

    private void exigirKyc(Usuario u) {
        if (!usuarios.verificarExpiracao(u)) {
            throw ApiException.proibido("Verificação de identidade (KYC) pendente: não é possível publicar agenda.");
        }
    }

    public Disponibilidade salvar(Usuario u, String veiculoId, DisponibilidadeReq req, String origem) {
        veiculoDoUsuario(u, veiculoId);
        exigirKyc(u);
        Map<LocalDate, Boolean> antes = disponibilidadeDoVeiculo(veiculoId).dias;
        LocalDate hoje = clock.hoje();
        Disponibilidade nova = new Disponibilidade(veiculoId);
        req.dias().forEach((dia, lib) -> {
            if (!dia.isBefore(hoje)) nova.dias.put(dia, Boolean.TRUE.equals(lib));
        });
        nova.aceiteAutomatico = req.aceiteAutomatico();
        long novos = nova.dias.entrySet().stream().filter(e -> e.getValue() && !Boolean.TRUE.equals(antes.get(e.getKey()))).count();
        store.disponibilidades.put(veiculoId, nova);
        if (novos > 0) bus.emitir("agenda_aberta", props("user_id", u.id, "vehicle_id", veiculoId, "dias_liberados", novos, "origem_acao", origem));
        return nova;
    }

    public Disponibilidade alternarDia(Usuario u, String veiculoId, LocalDate dia) {
        veiculoDoUsuario(u, veiculoId);
        exigirKyc(u);
        if (dia.isBefore(clock.hoje())) throw ApiException.regra("Não é possível alterar datas passadas.");
        Disponibilidade atual = disponibilidadeDoVeiculo(veiculoId);
        Disponibilidade nova = new Disponibilidade(veiculoId);
        nova.dias.putAll(atual.dias);
        nova.aceiteAutomatico = atual.aceiteAutomatico;
        boolean liberar = !Boolean.TRUE.equals(nova.dias.get(dia));
        nova.dias.put(dia, liberar);
        store.disponibilidades.put(veiculoId, nova);
        if (liberar) bus.emitir("agenda_aberta", props("user_id", u.id, "vehicle_id", veiculoId, "dias_liberados", 1, "origem_acao", "calendario"));
        return nova;
    }

    // ───────────── RF11 — liberar agenda com um toque ─────────────

    public TemplateDisponibilidade salvarTemplate(Usuario u, TemplateDisponibilidade t) {
        store.templates.put(u.id, t);
        return t;
    }

    public TemplateDisponibilidade template(Usuario u) {
        return store.templates.get(u.id);
    }

    /** Dias que o template libera a partir de hoje (apenas futuros/hoje). */
    public List<LocalDate> diasDoTemplate(TemplateDisponibilidade t) {
        LocalDate hoje = clock.hoje();
        int limite = t.tipo() == TemplateDisponibilidade.Tipo.SEXTA_A_DOMINGO ? Math.max(1, t.semanas()) * 7 : 14;
        List<LocalDate> fds = diasEntre(hoje, hoje.plusDays(limite)).stream().filter(d -> FIM_DE_SEMANA.contains(d.getDayOfWeek())).toList();
        if (t.tipo() == TemplateDisponibilidade.Tipo.SEXTA_A_DOMINGO) return fds;
        List<LocalDate> grupo = new ArrayList<>();
        for (LocalDate d : fds) {
            if (!grupo.isEmpty() && !d.equals(grupo.get(grupo.size() - 1).plusDays(1))) break;
            grupo.add(d);
        }
        return grupo;
    }

    public record Previa(List<LocalDate> dias, List<String> motivos) {
    }

    /** O que seria liberado e o que impede a publicação (mostrado antes de confirmar). */
    public Previa previaUmToque(Usuario u, String veiculoId) {
        Veiculo v = veiculoDoUsuario(u, veiculoId);
        TemplateDisponibilidade t = store.templates.get(u.id);
        if (t == null) return new Previa(List.of(), List.of("Crie seu template de disponibilidade primeiro."));
        LocalDate hoje = clock.hoje();
        List<LocalDate> dias = diasDoTemplate(t);
        List<String> motivos = new ArrayList<>();
        if (!usuarios.verificarExpiracao(u)) motivos.add("Verificação de identidade (KYC) não aprovada.");
        if (v.status != StatusVeiculo.APROVADO) motivos.add("Veículo inativo ou em análise.");
        if (v.docValidoAte != null && v.docValidoAte.isBefore(hoje)) motivos.add("Documentação ou seguro do veículo vencido.");
        if (dias.stream().anyMatch(d -> d.isBefore(hoje))) motivos.add("O template inclui datas no passado.");
        long conflitos = dias.stream().filter(d -> temConflito(veiculoId, d, d.plusDays(1), null)).count();
        if (conflitos > 0) motivos.add("Conflito com reserva existente em %d dia(s).".formatted(conflitos));
        if (dias.isEmpty()) motivos.add("O template não gerou nenhuma data.");
        return new Previa(dias, motivos);
    }

    public record ResultadoUmToque(List<LocalDate> dias, List<LocalDate> adicionados, Recompensa recompensa) {
    }

    public record Recompensa(long solicitacoesRecebidas, Long buscasCompativeis) {
    }

    /**
     * Publica as datas do template de uma vez. Aparece imediatamente na busca (que lê a mesma
     * agenda) e pode ser desfeito por JANELA_DESFAZER_AGENDA_MIN.
     */
    public ResultadoUmToque liberarComUmToque(Usuario u, String veiculoId, String origem) {
        Previa p = previaUmToque(u, veiculoId);
        if (!p.motivos().isEmpty()) throw ApiException.regra(String.join(" ", p.motivos()));
        Disponibilidade base = disponibilidadeDoVeiculo(veiculoId);
        List<LocalDate> adicionados = p.dias().stream().filter(d -> !Boolean.TRUE.equals(base.dias.get(d))).toList();
        Disponibilidade nova = new Disponibilidade(veiculoId);
        nova.dias.putAll(base.dias);
        nova.aceiteAutomatico = base.aceiteAutomatico;
        p.dias().forEach(d -> nova.dias.put(d, true));
        store.disponibilidades.put(veiculoId, nova);
        store.desfazer.put(veiculoId, new Store.Desfazer(adicionados, clock.maisMinutos(config.getInt(Parametro.JANELA_DESFAZER_AGENDA_MIN))));
        bus.emitir("agenda_aberta", props("user_id", u.id, "vehicle_id", veiculoId, "dias_liberados", adicionados.size(), "origem_acao", origem));
        return new ResultadoUmToque(p.dias(), adicionados, recompensa(veiculoId));
    }

    /** Solicitações reais + buscas compatíveis (7d) ou "dados insuficientes" — sempre como dado, nunca promessa de receita. */
    public Recompensa recompensa(String veiculoId) {
        Veiculo v = store.veiculos.get(veiculoId);
        var d = v == null ? null : demanda.demanda(v.local);
        long sol = store.reservas.values().stream().filter(r -> r.veiculoId.equals(veiculoId) && r.status != StatusReserva.AGUARDANDO_KYC).count();
        return new Recompensa(sol, d != null && d.suficiente() ? d.buscas() : null);
    }

    /** Desfaz a última liberação se ainda dentro da janela e sem reserva criada nesses dias. */
    public Disponibilidade desfazer(Usuario u, String veiculoId) {
        veiculoDoUsuario(u, veiculoId);
        var snap = store.desfazer.get(veiculoId);
        if (snap == null) throw ApiException.regra("Não há liberação para desfazer.");
        if (clock.passou(snap.expiraEm())) throw ApiException.regra("O prazo para desfazer terminou.");
        if (snap.adicionados().stream().anyMatch(d -> temConflito(veiculoId, d, d.plusDays(1), null))) {
            throw ApiException.conflito("Já existe reserva criada nesses dias: não é possível desfazer.");
        }
        Disponibilidade atual = store.disponibilidades.get(veiculoId);
        snap.adicionados().forEach(d -> atual.dias.put(d, false));
        store.desfazer.remove(veiculoId);
        bus.emitir("agenda_desfeita", props("user_id", u.id, "vehicle_id", veiculoId, "dias_removidos", snap.adicionados().size()));
        return atual;
    }

    // ───────────── RF17 — preferências e histórico ─────────────

    public PreferenciasAnuncio preferencias(String veiculoId) {
        return store.preferencias.getOrDefault(veiculoId, new PreferenciasAnuncio(0, 1));
    }

    public PreferenciasAnuncio salvarPreferencias(Usuario u, String veiculoId, PreferenciasAnuncio p) {
        veiculoDoUsuario(u, veiculoId);
        store.preferencias.put(veiculoId, p);
        return p;
    }

    /** Aplica as preferências salvas a uma nova reserva (erro claro se não atender). */
    public void validarPreferencias(String veiculoId, LocalDate inicio, LocalDate fim) {
        PreferenciasAnuncio p = preferencias(veiculoId);
        if (p.antecedenciaMinimaHoras() <= 0 && p.duracaoMinimaDias() <= 1) return;
        long horas = Duration.between(clock.now(), inicio.atStartOfDay(ClockService.ZONA).toInstant()).toHours();
        if (p.antecedenciaMinimaHoras() > 0 && horas < p.antecedenciaMinimaHoras()) {
            throw ApiException.regra("O proprietário exige antecedência mínima de %dh.".formatted(p.antecedenciaMinimaHoras()));
        }
        if (diasEntre(inicio, fim).size() < p.duracaoMinimaDias()) {
            throw ApiException.regra("O proprietário exige no mínimo %d diária(s).".formatted(p.duracaoMinimaDias()));
        }
    }

    /** Sugestão de datas: próximos dias nos dias da semana mais alugados e ainda não liberados. */
    public List<LocalDate> sugerirDatas(Usuario u, String veiculoId, int quantidade) {
        veiculoDoUsuario(u, veiculoId);
        Map<DayOfWeek, Integer> freq = new EnumMap<>(DayOfWeek.class);
        store.reservas.values().stream()
                .filter(r -> r.veiculoId.equals(veiculoId) && EnumSet.of(StatusReserva.CONFIRMADA, StatusReserva.EM_ANDAMENTO,
                        StatusReserva.EM_DISPUTA, StatusReserva.CONCLUIDA).contains(r.status))
                .forEach(r -> diasEntre(r.inicio, r.fim).forEach(d -> freq.merge(d.getDayOfWeek(), 1, Integer::sum)));
        if (freq.isEmpty()) return List.of();
        Set<DayOfWeek> topo = new HashSet<>();
        freq.entrySet().stream().sorted(Map.Entry.<DayOfWeek, Integer>comparingByValue().reversed()).limit(2).forEach(e -> topo.add(e.getKey()));
        LocalDate hoje = clock.hoje();
        Map<LocalDate, Boolean> atual = disponibilidadeDoVeiculo(veiculoId).dias;
        return diasEntre(hoje, hoje.plusDays(28)).stream()
                .filter(d -> topo.contains(d.getDayOfWeek()) && !Boolean.TRUE.equals(atual.get(d)))
                .limit(quantidade).toList();
    }
}
