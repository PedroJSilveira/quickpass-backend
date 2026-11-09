package br.com.quickpass.config;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.domain.Papel;
import br.com.quickpass.domain.Usuario;

/** Contexto da requisição corrente (usuário autenticado, papel ativo e sessão). */
public final class AuthContext {
    public record Contexto(Usuario usuario, Papel papelAtivo, String sessionId) {
    }

    private static final ThreadLocal<Contexto> ATUAL = new ThreadLocal<>();

    private AuthContext() {
    }

    static void definir(Contexto c) {
        ATUAL.set(c);
    }

    static void limpar() {
        ATUAL.remove();
    }

    public static Contexto atual() {
        return ATUAL.get();
    }

    /** Usuário autenticado ou 401. */
    public static Usuario usuario() {
        Contexto c = ATUAL.get();
        if (c == null || c.usuario() == null) throw ApiException.naoAutenticado("Autenticação necessária.");
        return c.usuario();
    }

    /** Usuário autenticado que tenha o papel exigido, ou 401/403. */
    public static Usuario exigir(Papel papel) {
        Usuario u = usuario();
        if (!u.temPapel(papel)) throw ApiException.proibido("Requer o papel " + papel + ".");
        return u;
    }
}
