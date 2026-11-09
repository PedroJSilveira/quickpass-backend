package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static br.com.quickpass.common.Util.props;

/**
 * Envio responsável de notificações de engajamento (RNF03): exige consentimento
 * de marketing em algum canal, respeita o limite global por semana e SEMPRE
 * registra o dado que justificou o envio.
 */
@Service
public class NotificationService {
    private static final long SEMANA_S = 7L * 24 * 3600;
    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;
    private final EventBus bus;

    public NotificationService(Store store, AppConfigService config, ClockService clock, EventBus bus) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.bus = bus;
    }

    /** Primeiro canal com consentimento de marketing do usuário, ou null. */
    public CanalNotificacao canalPermitido(Usuario u) {
        for (var c : CanalNotificacao.values()) if (u.permite(c, FinalidadeConsentimento.MARKETING)) return c;
        return null;
    }

    public long enviadasNaSemana(String userId, TipoNotificacao tipo) {
        Instant limite = clock.now().minusSeconds(SEMANA_S);
        return store.notificacoes.stream()
                .filter(n -> n.userId.equals(userId) && (tipo == null || n.tipo == tipo) && !n.enviadaEm.isBefore(limite))
                .count();
    }

    /**
     * Notificação de engajamento. Retorna null (nada enviado) sem consentimento,
     * com o limite global atingido, ou sem dado real que a justifique.
     */
    public Notificacao enviar(String userId, TipoNotificacao tipo, String titulo, String texto, String link, Map<String, Object> motivoDados) {
        if (motivoDados == null || motivoDados.isEmpty()) return null;
        Usuario u = store.usuarios.get(userId);
        if (u == null) return null;
        CanalNotificacao canal = canalPermitido(u);
        if (canal == null) return null;
        if (enviadasNaSemana(userId, null) >= config.getInt(Parametro.LIMITE_NOTIFICACOES_GLOBAL_SEMANA)) return null;
        return criar(u, canal, tipo, titulo, texto, link, motivoDados);
    }

    /** Notificação transacional (ex.: avaria informada): não depende de marketing nem conta no limite de engajamento. */
    public Notificacao enviarTransacional(String userId, TipoNotificacao tipo, String titulo, String texto, String link, Map<String, Object> motivoDados) {
        Usuario u = store.usuarios.get(userId);
        if (u == null) return null;
        for (var c : CanalNotificacao.values()) {
            if (u.permite(c, FinalidadeConsentimento.TRANSACIONAL)) return criar(u, c, tipo, titulo, texto, link, motivoDados);
        }
        return null;
    }

    private Notificacao criar(Usuario u, CanalNotificacao canal, TipoNotificacao tipo, String titulo, String texto, String link, Map<String, Object> motivo) {
        Notificacao n = new Notificacao();
        n.id = "N-" + store.proximo("notificacao", 1);
        n.userId = u.id;
        n.canal = canal;
        n.tipo = tipo;
        n.titulo = titulo;
        n.texto = texto;
        n.link = link;
        n.motivoDados = motivo;
        n.enviadaEm = clock.now();
        store.notificacoes.add(n);
        bus.emitirComo(u, "notificacao_enviada", props("notificacao_id", n.id, "user_id", u.id, "canal", canal.name().toLowerCase(),
                "tipo", tipo.name().toLowerCase(), "motivo_dados", motivo));
        return n;
    }

    public Notificacao abrir(Usuario u, String id) {
        Notificacao n = store.notificacoes.stream().filter(x -> x.id.equals(id) && x.userId.equals(u.id)).findFirst()
                .orElseThrow(() -> ApiException.naoEncontrado("Notificação não encontrada."));
        if (n.abertaEm == null) {
            n.abertaEm = clock.now();
            bus.emitir("notificacao_aberta", props("notificacao_id", id, "user_id", u.id, "canal", n.canal.name().toLowerCase(),
                    "tipo", n.tipo.name().toLowerCase()));
        }
        return n;
    }

    public List<Notificacao> daConta(String userId) {
        return store.notificacoes.stream().filter(n -> n.userId.equals(userId))
                .sorted((a, b) -> b.enviadaEm.compareTo(a.enviadaEm)).toList();
    }

    /** Última abertura de notificação (base da atribuição de retorno espontâneo — RF15). */
    public Instant ultimaInteracao(String userId) {
        return store.notificacoes.stream().filter(n -> n.userId.equals(userId) && n.abertaEm != null)
                .map(n -> n.abertaEm).max(Instant::compareTo).orElse(null);
    }
}
