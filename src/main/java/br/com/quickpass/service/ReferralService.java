package br.com.quickpass.service;

import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.config.Store.Identidade;
import br.com.quickpass.domain.Indicacao;
import br.com.quickpass.domain.StatusIndicacao;
import br.com.quickpass.domain.Usuario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.com.quickpass.common.Util.props;

/**
 * Sistema de indicação (RF10): link individual por usuário; a indicação só é
 * QUALIFICADA quando o convidado conclui e paga a primeira locação. Bloqueia
 * autoindicação (usuário/dispositivo/documento/cartão) e crédito duplicado;
 * bônus com teto por período. NÃO altera o Health Score.
 */
@Service
public class ReferralService {
    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;
    private final EventBus bus;
    private final String frontendUrl;

    public ReferralService(Store store, AppConfigService config, ClockService clock, EventBus bus,
                           @Value("${quickpass.frontend.url:http://localhost:4200}") String frontendUrl) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.bus = bus;
        this.frontendUrl = frontendUrl;
    }

    public Map<String, String> gerarLink(Usuario u) {
        if (u.codigoIndicacao == null) {
            u.codigoIndicacao = "QP" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            bus.emitirComo(u, "referral_link_gerado", props("referrer_id", u.id, "codigo", u.codigoIndicacao));
        }
        var atual = store.identidades.get(u.id);
        store.identidades.put(u.id, new Identidade(u.cpf, atual == null ? null : atual.dispositivo(), atual == null ? null : atual.cartao()));
        return Map.of("codigo", u.codigoIndicacao, "url", frontendUrl + "/cadastro?ref=" + u.codigoIndicacao);
    }

    public void registrarCartao(String userId, String cartaoHash) {
        var atual = store.identidades.get(userId);
        store.identidades.put(userId, new Identidade(atual == null ? null : atual.cpf(), atual == null ? null : atual.dispositivo(), cartaoHash));
    }

    private Optional<Usuario> donoDoCodigo(String codigo) {
        return store.usuarios.values().stream().filter(u -> codigo.equals(u.codigoIndicacao)).findFirst();
    }

    /** Registra o cadastro do convidado. false (nada registrado) se o código não existe, autoindicação ou duplicado. */
    public boolean registrarCadastro(String codigo, Usuario invitee, String dispositivo) {
        var dono = donoDoCodigo(codigo);
        if (dono.isEmpty()) return false;
        Usuario referrer = dono.get();
        var idDono = store.identidades.getOrDefault(referrer.id, new Identidade(referrer.cpf, null, null));
        boolean auto = referrer.id.equals(invitee.id)
                || (invitee.cpf != null && invitee.cpf.equals(idDono.cpf()))
                || (dispositivo != null && dispositivo.equals(idDono.dispositivo()));
        if (auto) return false;
        if (store.indicacoes.stream().anyMatch(i -> i.inviteeId.equals(invitee.id))) return false;
        store.identidades.put(invitee.id, new Identidade(invitee.cpf, dispositivo, null));
        Indicacao ind = new Indicacao();
        ind.referrerId = referrer.id;
        ind.inviteeId = invitee.id;
        ind.codigo = codigo;
        store.indicacoes.add(ind);
        bus.emitirComo(invitee, "referral_cadastro", props("referrer_id", referrer.id, "invitee_id", invitee.id, "codigo", codigo));
        return true;
    }

    /**
     * Chamado quando o convidado conclui E paga a primeira locação. Cada convidado gera no
     * máximo um crédito; o teto por período limita o bônus (a indicação continua qualificada).
     */
    public Indicacao qualificar(String inviteeId) {
        Indicacao ind = store.indicacoes.stream().filter(i -> i.inviteeId.equals(inviteeId)).findFirst().orElse(null);
        if (ind == null || ind.status == StatusIndicacao.QUALIFICADA) return null;
        // Cartão em comum com o indicador invalida a indicação (autoindicação disfarçada).
        var cInvitee = store.identidades.get(inviteeId);
        var cReferrer = store.identidades.get(ind.referrerId);
        if (cInvitee != null && cInvitee.cartao() != null && cReferrer != null && cInvitee.cartao().equals(cReferrer.cartao())) return null;
        double valor = config.get(Parametro.REFERRAL_BONUS_VALOR);
        double teto = config.get(Parametro.REFERRAL_TETO_PERIODO);
        String periodo = clock.now().toString().substring(0, 7);
        double creditado = store.indicacoes.stream()
                .filter(i -> i.referrerId.equals(ind.referrerId) && i.qualificadaEm != null && i.qualificadaEm.toString().startsWith(periodo))
                .mapToDouble(i -> i.bonus).sum();
        ind.bonus = valor > 0 && creditado + valor <= teto ? valor : 0;
        ind.status = StatusIndicacao.QUALIFICADA;
        ind.qualificadaEm = clock.now();
        bus.emitir("referral_qualificada", props("referrer_id", ind.referrerId, "invitee_id", inviteeId, "codigo", ind.codigo));
        if (ind.bonus > 0) {
            bus.emitir("referral_bonus_creditado", props("referrer_id", ind.referrerId, "invitee_id", inviteeId, "codigo", ind.codigo, "valor", ind.bonus));
        }
        return ind;
    }

    public List<Indicacao> doIndicador(String userId) {
        return store.indicacoes.stream().filter(i -> i.referrerId.equals(userId)).toList();
    }
}
