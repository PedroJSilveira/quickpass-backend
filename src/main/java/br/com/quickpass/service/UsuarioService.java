package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.*;
import br.com.quickpass.domain.*;
import br.com.quickpass.web.Dtos.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static br.com.quickpass.common.Util.props;
import static br.com.quickpass.common.Util.soDigitos;

/** Conta, papéis (locatário/proprietário/admin), sessão e KYC (RF02, RF08, RF15). */
@Service
public class UsuarioService {
    private static final Pattern MEDIA_PAGA = Pattern.compile("cpc|ppc|paid|paidsocial|display");
    private static final Pattern BUSCA = Pattern.compile("google|bing|duckduckgo", Pattern.CASE_INSENSITIVE);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Store store;
    private final ClockService clock;
    private final AppConfigService config;
    private final KycProvider kycProvider;
    private final EventBus bus;
    private final AuditService audit;
    private final ReferralService referral;
    private final NotificationService notificacoes;

    public UsuarioService(Store store, ClockService clock, AppConfigService config, KycProvider kycProvider, EventBus bus,
                          AuditService audit, ReferralService referral, NotificationService notificacoes) {
        this.store = store;
        this.clock = clock;
        this.config = config;
        this.kycProvider = kycProvider;
        this.bus = bus;
        this.audit = audit;
        this.referral = referral;
        this.notificacoes = notificacoes;
    }

    // ───────────── cadastro / sessão ─────────────

    /** Cria o usuário (id gerado); usado pelo cadastro público e pela carga de dados de demonstração. */
    public Usuario criar(String id, String nome, String email, String senha, String cpf, Set<Papel> papeis, int cnhAnos) {
        Usuario u = new Usuario();
        u.id = id;
        u.nome = nome.trim();
        u.email = email.trim().toLowerCase(Locale.ROOT);
        u.senhaHash = encoder.encode(senha);
        u.cpf = soDigitos(cpf);
        u.papeis = papeis.isEmpty() ? EnumSet.noneOf(Papel.class) : EnumSet.copyOf(papeis);
        u.cnhAnos = cnhAnos;
        u.membroDesde = clock.hoje();
        store.usuarios.put(id, u);
        return u;
    }

    public SessaoRes cadastrar(CadastroReq r) {
        if (r.papeis().contains(Papel.ADMIN)) throw ApiException.proibido("O papel ADMIN não pode ser auto-atribuído.");
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        if (porEmail(email) != null) throw ApiException.conflito("E-mail já cadastrado.");
        String cpf = soDigitos(r.cpf());
        if (!cpfValido(cpf)) throw ApiException.regra("CPF inválido.");
        if (store.usuarios.values().stream().anyMatch(u -> cpf.equals(u.cpf))) throw ApiException.conflito("CPF já cadastrado.");

        Usuario u = criar("U-" + store.proximo("usuario", 1000), r.nome(), email, r.senha(), cpf, r.papeis(), r.cnhAnos() == null ? 0 : r.cnhAnos());
        u.nota = 5.0;
        u.origem = classificarOrigem(r.atribuicao());
        if (u.origem.referralCode() != null) referral.registrarCadastro(u.origem.referralCode(), u, r.dispositivo());
        bus.emitirComo(u, "cadastro_concluido", props("user_id", u.id, "canal_origem", u.origem.canal().name().toLowerCase(),
                "utm_source", u.origem.utmSource(), "utm_medium", u.origem.utmMedium(), "utm_campaign", u.origem.utmCampaign(),
                "referral_code", u.origem.referralCode()));
        return new SessaoRes(novoToken(u), u);
    }

    public SessaoRes login(LoginReq r) {
        Usuario u = porEmail(r.email().trim().toLowerCase(Locale.ROOT));
        if (u == null || !u.ativo || !encoder.matches(r.senha(), u.senhaHash)) throw ApiException.naoAutenticado("E-mail ou senha inválidos.");
        iniciarSessao(u, r.origem(), r.notificacaoId());
        return new SessaoRes(novoToken(u), u);
    }

    public void logout(String token) {
        if (token != null) store.tokens.remove(token);
    }

    private String novoToken(Usuario u) {
        String t = UUID.randomUUID().toString();
        store.tokens.put(t, u.id);
        return t;
    }

    /**
     * Registra {@code sessao_iniciada} com a origem classificada (RF15). Retorno espontâneo =
     * sessão direta/link SEM abertura de notificação dentro da janela de atribuição.
     */
    public void iniciarSessao(Usuario u, String origemParam, String notificacaoId) {
        String origem = switch (origemParam == null ? "" : origemParam.toLowerCase(Locale.ROOT)) {
            case "push" -> "push";
            case "email" -> "email";
            case "link" -> "link";
            default -> notificacaoId != null ? "push" : "direta";
        };
        Instant ultima = notificacoes.ultimaInteracao(u.id);
        boolean atribuida = ultima != null
                && Duration.between(ultima, clock.now()).toMinutes() <= config.getInt(Parametro.JANELA_ATRIBUICAO_NOTIFICACAO_MIN);
        bus.emitirComo(u, "sessao_iniciada", props("origem", origem, "notificacao_id", notificacaoId,
                "ultima_interacao_notificacao", ultima, "retorno_espontaneo", (origem.equals("direta") || origem.equals("link")) && !atribuida));
    }

    // ───────────── papéis ─────────────

    /** Locatário/proprietário podem ser os dois; o próprio usuário só gerencia esses dois papéis. */
    public Usuario definirPapeis(Usuario u, Set<Papel> novos) {
        if (novos.contains(Papel.ADMIN)) throw ApiException.proibido("O papel ADMIN só pode ser concedido por um administrador.");
        EnumSet<Papel> resultado = EnumSet.copyOf(novos);
        if (u.temPapel(Papel.ADMIN)) resultado.add(Papel.ADMIN);
        u.papeis = resultado;
        return u;
    }

    public Usuario definirPapeisAdmin(String id, Set<Papel> novos) {
        Usuario u = obrigatorio(id);
        u.papeis = novos.isEmpty() ? EnumSet.noneOf(Papel.class) : EnumSet.copyOf(novos);
        return u;
    }

    // ───────────── KYC (RF02) ─────────────

    public Usuario enviarItemKyc(Usuario u, ItemKyc item) {
        StatusKyc antes = u.statusKyc;
        u.kyc.marcar(item);
        if (antes == StatusKyc.PENDENTE || antes == StatusKyc.REPROVADO || antes == StatusKyc.EXPIRADO) {
            mudarStatus(u, StatusKyc.EM_ANALISE);
            u.kycIniciadoEm = clock.now();
            bus.emitir("kyc_iniciado", props("user_id", u.id, "tipo_usuario", tipoUsuario(u)));
        }
        if (u.kyc.concluidos() == 4) concluirAnalise(u);
        return u;
    }

    private void concluirAnalise(Usuario u) {
        var res = kycProvider.analisar(u);
        long seg = u.kycIniciadoEm == null ? 0 : Duration.between(u.kycIniciadoEm, clock.now()).getSeconds();
        if (res.aprovado()) {
            u.validadeCnh = res.validadeCnh();
            u.kyc.aprovado = true;
            mudarStatus(u, StatusKyc.APROVADO);
            bus.emitir("kyc_aprovado", props("user_id", u.id, "tempo_analise_sec", seg, "tipo_usuario", tipoUsuario(u)));
        } else {
            mudarStatus(u, StatusKyc.REPROVADO);
            bus.emitir("kyc_reprovado", props("user_id", u.id, "tipo_usuario", tipoUsuario(u)));
        }
    }

    /** true se o KYC está aprovado e a CNH ainda é válida; marca EXPIRADO caso contrário. */
    public boolean verificarExpiracao(Usuario u) {
        if (u.statusKyc == StatusKyc.APROVADO && u.validadeCnh != null && clock.hoje().isAfter(u.validadeCnh)) {
            u.kyc.aprovado = false;
            mudarStatus(u, StatusKyc.EXPIRADO);
        }
        return u.statusKyc == StatusKyc.APROVADO;
    }

    /** Job: expira o KYC de quem teve a CNH vencida. */
    public void verificarExpiracaoTodos() {
        store.usuarios.values().forEach(this::verificarExpiracao);
    }

    private void mudarStatus(Usuario u, StatusKyc para) {
        StatusKyc de = u.statusKyc;
        if (de == para) return;
        u.statusKyc = para;
        audit.registrar(EntidadeAuditoria.KYC, u.id, de, para, u.id, null);
    }

    private String tipoUsuario(Usuario u) {
        return u.papeis.stream().filter(p -> p != Papel.ADMIN).sorted().findFirst().map(p -> p.name().toLowerCase()).orElse("visitante");
    }

    // ───────────── consentimento (RNF03) ─────────────

    public Usuario definirConsentimento(Usuario u, CanalNotificacao canal, FinalidadeConsentimento finalidade, boolean valor) {
        if (finalidade == FinalidadeConsentimento.TRANSACIONAL && !valor) {
            throw ApiException.regra("Notificações transacionais não podem ser desativadas.");
        }
        u.consentimentos.get(canal).put(finalidade, valor);
        return u;
    }

    // ───────────── consultas ─────────────

    public Usuario porEmail(String email) {
        return store.usuarios.values().stream().filter(u -> u.email.equals(email)).findFirst().orElse(null);
    }

    public Usuario obrigatorio(String id) {
        Usuario u = store.usuarios.get(id);
        if (u == null) throw ApiException.naoEncontrado("Usuário não encontrado: " + id);
        return u;
    }

    // ───────────── atribuição first-touch (RF08) ─────────────

    private Atribuicao classificarOrigem(AtribuicaoReq a) {
        if (a == null) return new Atribuicao(CanalOrigem.DIRETO, null, null, null, null, null, null, clock.now());
        CanalOrigem canal;
        String m = a.utmMedium() == null ? "" : a.utmMedium().toLowerCase(Locale.ROOT);
        if (a.ref() != null && !a.ref().isBlank()) canal = CanalOrigem.REFERRAL;
        else if (MEDIA_PAGA.matcher(m).matches()) canal = CanalOrigem.MIDIA_PAGA;
        else if (m.equals("hiperlocal") || m.equals("local")) canal = CanalOrigem.HIPERLOCAL;
        else if (m.equals("organic") || (a.referrer() != null && BUSCA.matcher(a.referrer()).find())) canal = CanalOrigem.BUSCA_ORGANICA;
        else if (a.utmSource() == null && a.utmMedium() == null && (a.referrer() == null || a.referrer().isBlank())) canal = CanalOrigem.DIRETO;
        else canal = CanalOrigem.DESCONHECIDO;
        String ref = a.ref() == null || a.ref().isBlank() ? null : a.ref();
        return new Atribuicao(canal, a.utmSource(), a.utmMedium(), a.utmCampaign(), a.utmTerm(), a.utmContent(), ref, clock.now());
    }

    /** Validação dos dígitos verificadores do CPF (rejeita sequências repetidas). */
    static boolean cpfValido(String cpf) {
        if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) return false;
        for (int t = 9; t < 11; t++) {
            int soma = 0;
            for (int i = 0; i < t; i++) soma += (cpf.charAt(i) - '0') * (t + 1 - i);
            int dv = (soma * 10) % 11 % 10;
            if (dv != cpf.charAt(t) - '0') return false;
        }
        return true;
    }
}
