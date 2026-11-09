package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.ItemKyc;
import br.com.quickpass.domain.Papel;
import br.com.quickpass.domain.Usuario;
import br.com.quickpass.service.HealthScoreService;
import br.com.quickpass.service.UsuarioService;
import br.com.quickpass.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** Cadastro, login, perfil, papéis, KYC e consentimentos. */
@RestController
@RequestMapping("/api")
public class AuthController {
    private final UsuarioService usuarios;
    private final HealthScoreService healthScore;

    public AuthController(UsuarioService usuarios, HealthScoreService healthScore) {
        this.usuarios = usuarios;
        this.healthScore = healthScore;
    }

    /** Cadastro com papéis LOCATARIO e/ou PROPRIETARIO e atribuição first-touch (UTMs / código de indicação). */
    @PostMapping("/auth/cadastro")
    @ResponseStatus(HttpStatus.CREATED)
    public SessaoRes cadastro(@Valid @RequestBody CadastroReq req) {
        return usuarios.cadastrar(req);
    }

    @PostMapping("/auth/login")
    public SessaoRes login(@Valid @RequestBody LoginReq req) {
        return usuarios.login(req);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) usuarios.logout(auth.substring(7).trim());
    }

    @GetMapping("/me")
    public Usuario me() {
        return AuthContext.usuario();
    }

    /** Define os papéis do próprio usuário (um usuário pode ser locatário e proprietário ao mesmo tempo). */
    @PutMapping("/me/papeis")
    public Usuario papeis(@Valid @RequestBody PapeisReq req) {
        return usuarios.definirPapeis(AuthContext.usuario(), req.papeis());
    }

    /** Início de sessão do app (retorno espontâneo x notificado — RF15). */
    @PostMapping("/me/sessao")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sessao(@RequestBody(required = false) Map<String, String> corpo) {
        usuarios.iniciarSessao(AuthContext.usuario(), corpo == null ? null : corpo.get("origem"), corpo == null ? null : corpo.get("notificacaoId"));
    }

    @GetMapping("/me/kyc")
    public Map<String, Object> kyc() {
        Usuario u = AuthContext.usuario();
        usuarios.verificarExpiracao(u);
        return Map.of("status", u.statusKyc, "itens", u.kyc, "itensConcluidos", u.kyc.concluidos(), "validadeCnh", String.valueOf(u.validadeCnh));
    }

    /** Envia o arquivo de um item do KYC (multipart, parte {@code arquivo}); com os 4 itens a análise é concluída. */
    @PostMapping(value = "/me/kyc/{item}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Usuario enviarKyc(@PathVariable ItemKyc item, @RequestPart("arquivo") MultipartFile arquivo) {
        return usuarios.enviarItemKyc(AuthContext.usuario(), item, arquivo);
    }

    @PutMapping("/me/consentimentos")
    public Usuario consentimento(@Valid @RequestBody ConsentimentoReq req) {
        return usuarios.definirConsentimento(AuthContext.usuario(), req.canal(), req.finalidade(), req.valor());
    }

    @GetMapping("/me/health-score")
    public Map<String, Object> healthScore(@RequestParam Papel papel) {
        Usuario u = AuthContext.exigir(papel);
        return Map.of("papel", papel, "score", healthScore.calcular(u.id, papel));
    }
}
