package br.com.quickpass.web;

import br.com.quickpass.config.*;
import br.com.quickpass.domain.*;
import br.com.quickpass.service.*;
import br.com.quickpass.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operação da plataforma (papel ADMIN): moderação, mediação de disputas, métricas, parâmetros e utilitários de demonstração. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final Store store;
    private final CatalogService catalog;
    private final DisputeService disputes;
    private final MetricsService metrics;
    private final ExperimentService experimento;
    private final AppConfigService config;
    private final AuditService audit;
    private final UsuarioService usuarios;
    private final ClockService clock;
    private final SchedulerService scheduler;
    private final MockPaymentGateway gateway;

    public AdminController(Store store, CatalogService catalog, DisputeService disputes, MetricsService metrics, ExperimentService experimento,
                           AppConfigService config, AuditService audit, UsuarioService usuarios, ClockService clock, SchedulerService scheduler,
                           MockPaymentGateway gateway) {
        this.store = store;
        this.catalog = catalog;
        this.disputes = disputes;
        this.metrics = metrics;
        this.experimento = experimento;
        this.config = config;
        this.audit = audit;
        this.usuarios = usuarios;
        this.clock = clock;
        this.scheduler = scheduler;
        this.gateway = gateway;
    }

    private Usuario admin() {
        return AuthContext.exigir(Papel.ADMIN);
    }

    // ───────────── moderação de veículos ─────────────

    @GetMapping("/veiculos")
    public List<Veiculo> veiculos(@RequestParam(defaultValue = "EM_ANALISE") StatusVeiculo status) {
        admin();
        return catalog.porStatus(status);
    }

    @PostMapping("/veiculos/{id}/aprovar")
    public Veiculo aprovar(@PathVariable String id) {
        admin();
        return catalog.moderar(id, true, null);
    }

    @PostMapping("/veiculos/{id}/recusar")
    public Veiculo recusar(@PathVariable String id, @RequestBody MotivoReq req) {
        admin();
        return catalog.moderar(id, false, req.motivo());
    }

    // ───────────── disputas ─────────────

    @GetMapping("/disputas")
    public List<Disputa> disputas(@RequestParam(defaultValue = "false") boolean somenteAbertas) {
        admin();
        return disputes.todas(somenteAbertas);
    }

    @PostMapping("/disputas/{id}/analise")
    public Disputa assumir(@PathVariable String id) {
        return disputes.assumirAnalise(id, admin());
    }

    @PostMapping("/disputas/{id}/decisao")
    public Disputa decidir(@PathVariable String id, @Valid @RequestBody DecisaoReq req) {
        return disputes.decidir(id, req, admin());
    }

    // ───────────── métricas ─────────────

    @GetMapping("/metricas")
    public Map<String, Object> metricas() {
        admin();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("northStar", metrics.northStar());
        m.put("guardrailDisputa", metrics.guardrailDisputa(null));
        m.put("guardrailChurnSilencioso", metrics.guardrailChurnSilencioso());
        m.put("retornoEspontaneoLocatario", metrics.retornoEspontaneo("locatario"));
        m.put("retornoEspontaneoProprietario", metrics.retornoEspontaneo("proprietario"));
        return m;
    }

    @GetMapping("/metricas/funil")
    public List<MetricsService.LinhaFunil> funil() {
        admin();
        return metrics.funilPorCanal();
    }

    @GetMapping("/metricas/retencao")
    public List<MetricsService.Coorte> retencao(@RequestParam Papel papel, @RequestParam(defaultValue = "4") int periodos) {
        admin();
        return metrics.retencaoPorCoorte(papel, periodos);
    }

    @GetMapping("/experimento")
    public Map<String, Object> experimento() {
        admin();
        return Map.of("estado", experimento.estado(), "ativo", experimento.ativo(), "resultado", experimento.resultado());
    }

    @GetMapping("/indicacoes")
    public List<Indicacao> indicacoes() {
        admin();
        return store.indicacoes;
    }

    // ───────────── auditoria / eventos ─────────────

    @GetMapping("/auditoria")
    public List<Auditoria> auditoria(@RequestParam EntidadeAuditoria entidade, @RequestParam String id) {
        admin();
        return audit.daEntidade(entidade, id);
    }

    @GetMapping("/eventos")
    public List<EventoAnalitico> eventos(@RequestParam(required = false) String nome, @RequestParam(defaultValue = "200") int limite) {
        admin();
        var lista = store.eventos.stream().filter(e -> nome == null || e.eventName().equals(nome)).toList();
        return lista.subList(Math.max(0, lista.size() - limite), lista.size());
    }

    // ───────────── usuários ─────────────

    @GetMapping("/usuarios")
    public List<Usuario> usuarios() {
        admin();
        return List.copyOf(store.usuarios.values());
    }

    @PutMapping("/usuarios/{id}/papeis")
    public Usuario papeis(@PathVariable String id, @RequestBody PapeisReq req) {
        admin();
        return usuarios.definirPapeisAdmin(id, req.papeis());
    }

    // ───────────── parâmetros (§11) ─────────────

    @GetMapping("/config")
    public Map<String, Object> config() {
        admin();
        return config.todos();
    }

    @PutMapping("/config/{chave}")
    public Map<String, Object> definir(@PathVariable Parametro chave, @Valid @RequestBody ParametroReq req) {
        admin();
        config.definir(chave, req.valor());
        return config.todos();
    }

    @DeleteMapping("/config/{chave}")
    public Map<String, Object> restaurar(@PathVariable Parametro chave) {
        admin();
        config.restaurar(chave);
        return config.todos();
    }

    // ───────────── utilitários de demonstração ─────────────

    /** Roda todos os jobs agora (expiração, caução, repasse, radar, lembretes). */
    @PostMapping("/jobs/executar")
    public Map<String, String> executarJobs() {
        admin();
        scheduler.tick();
        return Map.of("status", "ok");
    }

    /** Avança o relógio da aplicação (simula a passagem do tempo) e roda os jobs. */
    @PostMapping("/relogio/avancar")
    public Map<String, Object> avancar(@Valid @RequestBody AvancarRelogioReq req) {
        admin();
        clock.avancar(req.horas() * 3_600_000L);
        scheduler.tick();
        return Map.of("agora", clock.now(), "deslocamentoHoras", clock.offsetMs() / 3_600_000.0);
    }

    @PostMapping("/simulacao/gateway")
    public GatewayReq gateway(@RequestBody GatewayReq req) {
        admin();
        gateway.falharCobranca = req.falharCobranca();
        gateway.falharPreAutorizacao = req.falharPreAutorizacao();
        return req;
    }
}
