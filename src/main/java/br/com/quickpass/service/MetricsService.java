package br.com.quickpass.service;

import br.com.quickpass.common.Datas;
import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/** Métricas do documento (§8): North Star, guardrails, funil AARRR e retenção, computáveis a partir dos dados e eventos. */
@Service
public class MetricsService {
    public record LinhaNorthStar(String semana, int locacoes, double gmvLiquido) {
    }

    public record Guardrail(long numerador, long denominador, double pct, double meta, boolean violado) {
    }

    public record LinhaFunil(String canal, int aquisicao, int ativacaoLocatario, int ativacaoProprietario, int marcoAgendaAberta,
                             double receita, int recomendacao, double taxaAtivacaoLocatario) {
    }

    public record Coorte(long coorte, String granularidade, int tamanho, List<Double> espontanea, List<Double> notificada) {
    }

    public record RetornoEspontaneo(long sessoes, long espontaneas, long notificadas, double pct) {
    }

    private final Store store;
    private final AppConfigService config;
    private final EventBus bus;
    private final SupplyHealthService supply;

    public MetricsService(Store store, AppConfigService config, EventBus bus, SupplyHealthService supply) {
        this.store = store;
        this.config = config;
        this.bus = bus;
        this.supply = supply;
    }

    // ───────────── North Star (8.1) ─────────────

    /** Transação elegível: CONCLUIDA, pagamento finalizado, sem estorno total, caução resolvida. */
    private List<Reserva> elegiveis() {
        return store.reservas.values().stream().filter(r -> {
            if (r.status != StatusReserva.CONCLUIDA) return false;
            Caucao c = store.caucoes.get(r.id);
            boolean resolvida = c != null && EnumSet.of(StatusCaucao.LIBERADA, StatusCaucao.CAPTURADA_TOTAL, StatusCaucao.CAPTURADA_PARCIAL).contains(c.status);
            Pagamento p = store.pagamentos.get(r.id);
            return resolvida && p != null && (p.status == StatusPagamento.FINALIZADO || p.status == StatusPagamento.ESTORNADO_PARCIAL);
        }).toList();
    }

    /** Locações concluídas elegíveis e GMV líquido por semana ISO de conclusão. */
    public List<LinhaNorthStar> northStar() {
        Map<String, double[]> porSemana = new TreeMap<>();
        for (Reserva r : elegiveis()) {
            Pagamento p = store.pagamentos.get(r.id);
            double[] acc = porSemana.computeIfAbsent(Datas.semanaIso(r.concluidaEm != null ? r.concluidaEm : r.criadaEm), k -> new double[2]);
            acc[0] += 1;
            acc[1] += p.total - p.estornado;
        }
        return porSemana.entrySet().stream().map(e -> new LinhaNorthStar(e.getKey(), (int) e.getValue()[0], e.getValue()[1])).toList();
    }

    // ───────────── Guardrails (8.2) ─────────────

    /** Locações concluídas com disputa / locações concluídas (na semana informada, ou no total). */
    public Guardrail guardrailDisputa(String semana) {
        var concluidas = store.reservas.values().stream()
                .filter(r -> r.status == StatusReserva.CONCLUIDA)
                .filter(r -> semana == null || Datas.semanaIso(r.concluidaEm != null ? r.concluidaEm : r.criadaEm).equals(semana)).toList();
        long comDisputa = concluidas.stream().filter(r -> store.disputas.values().stream().anyMatch(d -> d.reservaId.equals(r.id))).count();
        return guardrail(comDisputa, concluidas.size(), config.get(Parametro.META_GUARDRAIL_DISPUTA));
    }

    public Guardrail guardrailChurnSilencioso() {
        var g = supply.guardrailChurn();
        return guardrail(g.numerador(), g.denominador(), config.get(Parametro.META_GUARDRAIL_CHURN_SILENCIOSO));
    }

    private Guardrail guardrail(long num, long den, double meta) {
        double pct = den == 0 ? 0 : (double) num / den * 100;
        return new Guardrail(num, den, pct, meta, den > 0 && pct >= meta);
    }

    // ───────────── Funil AARRR (8.3) ─────────────

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    public List<LinhaFunil> funilPorCanal() {
        Map<String, String> canalDe = new LinkedHashMap<>();
        bus.eventos("cadastro_concluido").forEach(e -> canalDe.put(str(e.props().get("user_id")), str(e.props().get("canal_origem"))));
        var transacoes = bus.eventos("transacao_concluida");
        Set<String> listaram = bus.eventos("veiculo_listado").stream().map(e -> str(e.props().get("user_id"))).collect(Collectors.toSet());
        Set<String> abriram = bus.eventos("agenda_aberta").stream().map(e -> str(e.userId())).collect(Collectors.toSet());
        var qualificadas = bus.eventos("referral_qualificada");
        List<LinhaFunil> out = new ArrayList<>();
        for (String canal : new LinkedHashSet<>(canalDe.values())) {
            Set<String> usuarios = canalDe.entrySet().stream().filter(e -> e.getValue().equals(canal)).map(Map.Entry::getKey).collect(Collectors.toSet());
            Set<String> ativLoc = transacoes.stream().map(e -> str(e.props().get("locatario_id"))).filter(usuarios::contains).collect(Collectors.toSet());
            Set<String> ativProp = transacoes.stream().map(e -> str(e.props().get("owner_id"))).filter(o -> usuarios.contains(o) && listaram.contains(o)).collect(Collectors.toSet());
            double receita = transacoes.stream().filter(e -> usuarios.contains(str(e.props().get("locatario_id"))))
                    .mapToDouble(e -> num(e.props().get("taxa_plataforma")) - num(e.props().get("desconto"))).sum();
            int recomendacao = (int) qualificadas.stream().filter(e -> usuarios.contains(str(e.props().get("referrer_id")))).count();
            out.add(new LinhaFunil(canal, usuarios.size(), ativLoc.size(), ativProp.size(), (int) usuarios.stream().filter(abriram::contains).count(),
                    receita, recomendacao, usuarios.isEmpty() ? 0 : (double) ativLoc.size() / usuarios.size() * 100));
        }
        return out;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    // ───────────── Retenção e retorno espontâneo (RF15, 8.3/8.4) ─────────────

    private static final long EPOCH_SEG_DIAS = LocalDate.of(1970, 1, 5).toEpochDay(); // uma segunda-feira

    private long indicePeriodo(Instant i, boolean semana) {
        LocalDate d = i.atZone(ZoneOffset.UTC).toLocalDate();
        return semana ? Math.floorDiv(d.toEpochDay() - EPOCH_SEG_DIAS, 7) : d.getYear() * 12L + d.getMonthValue() - 1;
    }

    /**
     * Retenção por coorte (semana de cadastro para proprietário; mês para locatário) separando
     * retorno ESPONTÂNEO de NOTIFICADO. {@code espontanea[k-1]} = % da coorte com sessão espontânea no período k.
     */
    public List<Coorte> retencaoPorCoorte(Papel papel, int periodos) {
        boolean semana = papel == Papel.PROPRIETARIO;
        String gran = semana ? "semana" : "mes";
        record Cad(String user, long idx) {
        }
        List<Cad> cadastros = bus.eventos("cadastro_concluido").stream().map(e -> new Cad(str(e.props().get("user_id")), indicePeriodo(e.timestamp(), semana))).toList();
        var sessoes = bus.eventos("sessao_iniciada").stream().filter(e -> papel.name().equalsIgnoreCase(e.papel())).toList();
        List<Coorte> out = new ArrayList<>();
        for (long idx : cadastros.stream().map(Cad::idx).distinct().sorted().toList()) {
            Set<String> membros = cadastros.stream().filter(c -> c.idx() == idx).map(Cad::user).collect(Collectors.toSet());
            List<Double> esp = new ArrayList<>(), notif = new ArrayList<>();
            for (int k = 1; k <= periodos; k++) {
                final int kk = k;
                for (boolean espontanea : new boolean[]{true, false}) {
                    long voltaram = sessoes.stream()
                            .filter(s -> membros.contains(str(s.userId())) && indicePeriodo(s.timestamp(), semana) == idx + kk
                                    && Boolean.TRUE.equals(s.props().get("retorno_espontaneo")) == espontanea)
                            .map(s -> s.userId()).distinct().count();
                    (espontanea ? esp : notif).add(membros.isEmpty() ? 0 : (double) voltaram / membros.size() * 100);
                }
            }
            out.add(new Coorte(idx, gran, membros.size(), esp, notif));
        }
        return out;
    }

    /** % de sessões com retorno espontâneo (sem abertura de notificação atribuída). */
    public RetornoEspontaneo retornoEspontaneo(String papel) {
        var sessoes = bus.eventos("sessao_iniciada").stream().filter(e -> papel == null || papel.equalsIgnoreCase(e.papel())).toList();
        long esp = sessoes.stream().filter(e -> Boolean.TRUE.equals(e.props().get("retorno_espontaneo"))).count();
        return new RetornoEspontaneo(sessoes.size(), esp, sessoes.size() - esp, sessoes.isEmpty() ? 0 : (double) esp / sessoes.size() * 100);
    }
}
