package br.com.quickpass.config;

import br.com.quickpass.domain.Papel;
import br.com.quickpass.domain.Usuario;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 1) Resolve o usuário a partir de {@code Authorization: Bearer <token>} (opcional:
 * cada endpoint decide se exige login); 2) serializa a requisição com o lock do Store.
 * Cabeçalhos opcionais: {@code X-Papel-Ativo} (LOCATARIO|PROPRIETARIO) e {@code X-Session-Id}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final String ATTR_LOCK = "qp.lock";
    private final Store store;

    public AuthInterceptor(Store store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        store.lock.lock();
        req.setAttribute(ATTR_LOCK, true);
        Usuario usuario = null;
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String userId = store.tokens.get(auth.substring(7).trim());
            Usuario u = userId == null ? null : store.usuarios.get(userId);
            if (u != null && u.ativo) usuario = u;
        }
        Papel ativo = null;
        String header = req.getHeader("X-Papel-Ativo");
        if (usuario != null) {
            if (header != null) {
                try {
                    Papel p = Papel.valueOf(header.trim().toUpperCase());
                    if (usuario.temPapel(p)) ativo = p;
                } catch (IllegalArgumentException ignorado) {
                    // papel inválido: cai no padrão
                }
            }
            if (ativo == null) ativo = usuario.papeis.stream().sorted().findFirst().orElse(null);
        }
        AuthContext.definir(new AuthContext.Contexto(usuario, ativo, req.getHeader("X-Session-Id")));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.limpar();
        if (req.getAttribute(ATTR_LOCK) != null && store.lock.isHeldByCurrentThread()) store.lock.unlock();
    }
}
