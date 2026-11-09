package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.*;
import br.com.quickpass.web.Dtos.BuscaSalvaReq;
import org.springframework.stereotype.Service;

import java.util.List;

import static br.com.quickpass.common.Util.props;

/**
 * Busca salva e Radar de Disponibilidade (RF14): ciclo de hábito do locatário. Alerta SOMENTE com
 * correspondência real (veículo ativo e agenda livre no período), com consentimento, sem repetir o mesmo veículo.
 */
@Service
public class SavedSearchService {
    private final Store store;
    private final ClockService clock;
    private final EventBus bus;
    private final CatalogService catalog;
    private final NotificationService notificacoes;

    public SavedSearchService(Store store, ClockService clock, EventBus bus, CatalogService catalog, NotificationService notificacoes) {
        this.store = store;
        this.clock = clock;
        this.bus = bus;
        this.catalog = catalog;
        this.notificacoes = notificacoes;
    }

    public List<BuscaSalva> doLocatario(String renterId) {
        return store.buscasSalvas.values().stream().filter(b -> b.renterId.equals(renterId)).toList();
    }

    public BuscaSalva obrigatoria(Usuario u, String id) {
        BuscaSalva b = store.buscasSalvas.get(id);
        if (b == null || !b.renterId.equals(u.id)) throw ApiException.naoEncontrado("Busca salva não encontrada.");
        return b;
    }

    public BuscaSalva salvar(Usuario u, BuscaSalvaReq r) {
        if (!r.fim().isAfter(r.inicio())) throw ApiException.regra("O período da busca é inválido.");
        BuscaSalva b = new BuscaSalva();
        b.id = "SS-" + store.proximo("buscaSalva", 1);
        b.renterId = u.id;
        b.regiao = r.regiao();
        b.raioKm = r.raioKm();
        b.inicio = r.inicio();
        b.fim = r.fim();
        b.categoria = r.categoria() == null || r.categoria().isBlank() ? "Todos" : r.categoria();
        b.precoMax = r.precoMax();
        b.alertasAtivos = r.alertasAtivos();
        b.criadaEm = clock.now();
        // Ativar alertas = consentimento explícito de push (marketing), dado pelo usuário no formulário.
        if (r.alertasAtivos() && r.canalConsentido()) u.consentimentos.get(CanalNotificacao.PUSH).put(FinalidadeConsentimento.MARKETING, true);
        store.buscasSalvas.put(b.id, b);
        bus.emitir("busca_salva", props("saved_search_id", b.id, "user_id", u.id, "categoria", b.categoria, "regiao_aproximada", b.regiao));
        return b;
    }

    public BuscaSalva pausar(Usuario u, String id, boolean pausada) {
        BuscaSalva b = obrigatoria(u, id);
        b.pausada = pausada;
        return b;
    }

    public void excluir(Usuario u, String id) {
        store.buscasSalvas.remove(obrigatoria(u, id).id);
    }

    /** Veículos que atendem à busca agora: ativos, na região/categoria/preço e com agenda livre no período. */
    public List<Veiculo> correspondencias(BuscaSalva b) {
        return store.veiculos.values().stream()
                .filter(v -> v.status == StatusVeiculo.APROVADO && !v.proprietarioId.equals(b.renterId))
                .filter(v -> b.regiao == null || b.regiao.isBlank() || v.local.equalsIgnoreCase(b.regiao))
                .filter(v -> "Todos".equalsIgnoreCase(b.categoria) || v.categoria.rotulo().equalsIgnoreCase(b.categoria))
                .filter(v -> b.precoMax == null || v.diaria <= b.precoMax)
                .filter(v -> v.distanciaKm <= b.raioKm)
                .filter(v -> catalog.estaLivre(v.id, b.inicio, b.fim))
                .toList();
    }

    /** Job do Radar: no máximo um alerta por (busca, veículo). Sem match, nada. */
    public void verificarMatches() {
        for (BuscaSalva b : store.buscasSalvas.values()) {
            if (!b.alertasAtivos || b.pausada) continue;
            for (Veiculo v : correspondencias(b)) {
                String chave = b.id + ":" + v.id;
                if (store.alertados.contains(chave)) continue;
                Notificacao n = notificacoes.enviar(b.renterId, TipoNotificacao.RADAR, "Carro disponível para sua busca",
                        "%s em %s está livre de %s a %s.".formatted(v.titulo, v.local, b.inicio, b.fim),
                        "/carros/%s?origem=radar&busca=%s".formatted(v.id, b.id),
                        props("saved_search_id", b.id, "vehicle_id", v.id, "periodo", List.of(b.inicio.toString(), b.fim.toString())));
                if (n != null) {
                    store.alertados.add(chave);
                    bus.emitir("radar_alerta_enviado", props("saved_search_id", b.id, "vehicle_id", v.id, "notificacao_id", n.id));
                }
            }
        }
    }

    /** O usuário abriu o alerta do Radar. */
    public Notificacao registrarAbertura(Usuario u, String notificacaoId, String buscaId, String veiculoId) {
        Notificacao n = notificacoes.abrir(u, notificacaoId);
        bus.emitir("radar_alerta_aberto", props("saved_search_id", buscaId, "vehicle_id", veiculoId));
        return n;
    }
}
