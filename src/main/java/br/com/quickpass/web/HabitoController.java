package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.*;
import br.com.quickpass.service.NotificationService;
import br.com.quickpass.service.ReferralService;
import br.com.quickpass.service.SavedSearchService;
import br.com.quickpass.web.Dtos.BuscaSalvaReq;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Geração de hábito e growth: buscas salvas + Radar (RF14), notificações (RNF03) e indicações (RF10). */
@RestController
@RequestMapping("/api")
public class HabitoController {
    private final SavedSearchService buscas;
    private final NotificationService notificacoes;
    private final ReferralService referral;

    public HabitoController(SavedSearchService buscas, NotificationService notificacoes, ReferralService referral) {
        this.buscas = buscas;
        this.notificacoes = notificacoes;
        this.referral = referral;
    }

    // ───────────── buscas salvas / radar ─────────────

    @GetMapping("/buscas-salvas")
    public List<BuscaSalva> listar() {
        return buscas.doLocatario(AuthContext.exigir(Papel.LOCATARIO).id);
    }

    @PostMapping("/buscas-salvas")
    @ResponseStatus(HttpStatus.CREATED)
    public BuscaSalva salvar(@Valid @RequestBody BuscaSalvaReq req) {
        return buscas.salvar(AuthContext.exigir(Papel.LOCATARIO), req);
    }

    @PatchMapping("/buscas-salvas/{id}/pausa")
    public BuscaSalva pausar(@PathVariable String id, @RequestParam(defaultValue = "true") boolean pausada) {
        return buscas.pausar(AuthContext.exigir(Papel.LOCATARIO), id, pausada);
    }

    @DeleteMapping("/buscas-salvas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable String id) {
        buscas.excluir(AuthContext.exigir(Papel.LOCATARIO), id);
    }

    /** Veículos que atendem à busca salva agora. */
    @GetMapping("/buscas-salvas/{id}/correspondencias")
    public List<Veiculo> correspondencias(@PathVariable String id) {
        Usuario u = AuthContext.exigir(Papel.LOCATARIO);
        return buscas.correspondencias(buscas.obrigatoria(u, id)).stream().toList();
    }

    // ───────────── notificações ─────────────

    @GetMapping("/notificacoes")
    public List<Notificacao> notificacoes() {
        return notificacoes.daConta(AuthContext.usuario().id);
    }

    @PostMapping("/notificacoes/{id}/abrir")
    public Notificacao abrir(@PathVariable String id,
                             @RequestParam(required = false) String busca, @RequestParam(required = false) String veiculo) {
        Usuario u = AuthContext.usuario();
        // Alerta do Radar abre também registra o evento específico (radar_alerta_aberto).
        if (busca != null && veiculo != null) return buscas.registrarAbertura(u, id, busca, veiculo);
        return notificacoes.abrir(u, id);
    }

    // ───────────── indicações ─────────────

    /** Link individual de indicação do usuário. */
    @GetMapping("/indicacoes/link")
    public Map<String, String> link() {
        return referral.gerarLink(AuthContext.usuario());
    }

    @GetMapping("/indicacoes")
    public List<Indicacao> indicacoes() {
        return referral.doIndicador(AuthContext.usuario().id);
    }
}
