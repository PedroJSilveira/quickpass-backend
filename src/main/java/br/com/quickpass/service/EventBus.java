package br.com.quickpass.service;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.EventoAnalitico;
import br.com.quickpass.domain.Usuario;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Barramento de eventos analíticos (§7). Guarda em memória; em produção
 * encaminharia para o pipeline de analytics. Localização é sempre aproximada
 * (RNF02): propriedades que pareçam lat/long exata são descartadas.
 */
@Service
public class EventBus {
    private static final Set<String> PROIBIDAS = Set.of("lat", "lng", "lon", "latitude", "longitude", "lat_long", "lat_long_centro");
    private final Store store;
    private final ClockService clock;

    public EventBus(Store store, ClockService clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Emite com o contexto da requisição (usuário/papel/sessão); jobs emitem sem usuário. */
    public void emitir(String nome, Map<String, Object> props) {
        var ctx = AuthContext.atual();
        String uid = ctx != null && ctx.usuario() != null ? ctx.usuario().id : null;
        String papel = ctx != null && ctx.papelAtivo() != null ? ctx.papelAtivo().name().toLowerCase() : "visitante";
        registrar(nome, uid, papel, ctx != null ? ctx.sessionId() : null, props);
    }

    /** Emite em nome de um usuário específico (ex.: cadastro, antes de existir sessão). */
    public void emitirComo(Usuario u, String nome, Map<String, Object> props) {
        String papel = u.papeis.stream().sorted().findFirst().map(p -> p.name().toLowerCase()).orElse("visitante");
        var ctx = AuthContext.atual();
        registrar(nome, u.id, papel, ctx != null ? ctx.sessionId() : null, props);
    }

    private void registrar(String nome, String uid, String papel, String sessao, Map<String, Object> props) {
        Map<String, Object> limpo = new java.util.LinkedHashMap<>();
        props.forEach((k, v) -> {
            if (!PROIBIDAS.contains(k.toLowerCase())) limpo.put(k, v);
        });
        store.eventos.add(new EventoAnalitico(nome, clock.now(), uid, papel, sessao, limpo));
    }

    public List<EventoAnalitico> eventos(String nome) {
        return store.eventos.stream().filter(e -> e.eventName().equals(nome)).toList();
    }
}
