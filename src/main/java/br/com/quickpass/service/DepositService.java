package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.*;
import br.com.quickpass.domain.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

import static br.com.quickpass.common.Util.props;

/**
 * Caução (RF04, §4.3). Regra central: a caução NUNCA é capturada sem decisão registrada — a
 * única via de captura é {@link #aplicarDecisao}, que exige uma decisão completa.
 */
@Service
public class DepositService {
    private final Store store;
    private final PaymentGateway gateway;
    private final EventBus bus;
    private final AppConfigService config;
    private final AuditService audit;
    private final ClockService clock;

    public DepositService(Store store, PaymentGateway gateway, EventBus bus, AppConfigService config, AuditService audit, ClockService clock) {
        this.store = store;
        this.gateway = gateway;
        this.bus = bus;
        this.config = config;
        this.audit = audit;
        this.clock = clock;
    }

    public Caucao porReserva(String reservaId) {
        return store.caucoes.get(reservaId);
    }

    /** Caução resolvida = não há mais pendência financeira sobre ela. */
    public boolean resolvida(String reservaId) {
        Caucao c = porReserva(reservaId);
        return c != null && (c.status == StatusCaucao.LIBERADA || c.status == StatusCaucao.CAPTURADA_TOTAL || c.status == StatusCaucao.CAPTURADA_PARCIAL);
    }

    /** Dados de demonstração. */
    public void semear(String reservaId, double valor, StatusCaucao status) {
        Caucao c = new Caucao();
        c.reservaId = reservaId;
        c.valor = valor;
        c.status = status;
        c.preAutorizadaEm = clock.now();
        c.validaAte = clock.maisHoras(24 * 14);
        store.caucoes.put(reservaId, c);
    }

    /** Pré-autoriza a caução; a validade cobre locação + prazo de liberação + contestação (RF04). */
    public boolean preAutorizar(String reservaId, double valor, LocalDate fimLocacao, String cartao) {
        long extras = config.getInt(Parametro.PRAZO_LIBERACAO_CAUCAO_HORAS) + config.getInt(Parametro.PRAZO_CONTESTACAO_HORAS);
        Instant validaAte = fimLocacao.atTime(23, 59, 59).atZone(ClockService.ZONA).toInstant().plusSeconds(extras * 3600);
        Caucao c = new Caucao();
        c.reservaId = reservaId;
        c.valor = valor;
        c.status = StatusCaucao.PENDENTE_PRE_AUTORIZACAO;
        c.validaAte = validaAte;
        store.caucoes.put(reservaId, c);
        var r = gateway.preAutorizar(reservaId, valor, validaAte, cartao);
        if (!r.ok()) {
            mudar(c, StatusCaucao.FALHA, "gateway", r.motivo());
            bus.emitir("caucao_falhou", props("reservation_id", reservaId, "valor", valor));
            return false;
        }
        c.preAutorizadaEm = clock.now();
        mudar(c, StatusCaucao.PRE_AUTORIZADA, "gateway", null);
        bus.emitir("caucao_pre_autorizada", props("reservation_id", reservaId, "valor", valor));
        return true;
    }

    /** Check-out sem ocorrência: agenda a liberação automática após PRAZO_LIBERACAO_CAUCAO_HORAS. */
    public void agendarLiberacao(String reservaId) {
        Caucao c = porReserva(reservaId);
        if (c != null && c.status == StatusCaucao.PRE_AUTORIZADA) c.liberarEm = clock.maisHoras(config.getInt(Parametro.PRAZO_LIBERACAO_CAUCAO_HORAS));
    }

    /** Avaria informada: caução fica retida até a decisão (não captura nada). */
    public void reter(String reservaId) {
        Caucao c = porReserva(reservaId);
        if (c == null || c.status != StatusCaucao.PRE_AUTORIZADA) throw ApiException.regra("Só é possível reter uma caução pré-autorizada.");
        c.liberarEm = null;
        mudar(c, StatusCaucao.RETIDA_EM_DISPUTA, "sistema", null);
    }

    /** Única via de captura/liberação pós-disputa: exige decisão registrada e válida. */
    public void aplicarDecisao(String reservaId, DecisaoDisputa d) {
        Caucao c = porReserva(reservaId);
        if (c == null || c.status != StatusCaucao.RETIDA_EM_DISPUTA) throw ApiException.regra("Caução não está retida em disputa.");
        if (d == null || d.decididaPor() == null || d.justificativa() == null || d.justificativa().isBlank() || d.decididaEm() == null) {
            throw ApiException.regra("Captura exige decisão registrada com justificativa e responsável.");
        }
        if (d.tipo() == TipoDecisao.CAPTURA_TOTAL && d.valor() != c.valor) throw ApiException.regra("Captura total deve ser igual ao valor da caução.");
        if (d.tipo() == TipoDecisao.CAPTURA_PARCIAL && !(d.valor() > 0 && d.valor() < c.valor)) {
            throw ApiException.regra("Captura parcial deve estar entre 0 e o valor da caução.");
        }
        if (d.tipo() == TipoDecisao.LIBERACAO) {
            gateway.liberar(reservaId);
            c.valorCapturado = 0;
            mudar(c, StatusCaucao.LIBERADA, d.decididaPor(), d.justificativa());
            bus.emitir("caucao_liberada", props("reservation_id", reservaId, "valor", c.valor));
            return;
        }
        gateway.capturar(reservaId, d.valor());
        c.valorCapturado = d.valor();
        mudar(c, d.tipo() == TipoDecisao.CAPTURA_TOTAL ? StatusCaucao.CAPTURADA_TOTAL : StatusCaucao.CAPTURADA_PARCIAL, d.decididaPor(), d.justificativa());
        bus.emitir("caucao_capturada", props("reservation_id", reservaId, "valor", d.valor()));
    }

    /** Cancelamento antes do check-in: devolve a pré-autorização. */
    public void liberarPorCancelamento(String reservaId) {
        Caucao c = porReserva(reservaId);
        if (c == null || c.status != StatusCaucao.PRE_AUTORIZADA) return;
        gateway.liberar(reservaId);
        mudar(c, StatusCaucao.LIBERADA, "sistema", "cancelamento");
        bus.emitir("caucao_liberada", props("reservation_id", reservaId, "valor", c.valor));
    }

    /** Job: libera cauções sem ocorrência cujo prazo venceu. */
    public void processarLiberacoes() {
        for (Caucao c : store.caucoes.values()) {
            if (c.status == StatusCaucao.PRE_AUTORIZADA && c.liberarEm != null && clock.passou(c.liberarEm)) {
                gateway.liberar(c.reservaId);
                mudar(c, StatusCaucao.LIBERADA, "sistema", "prazo sem ocorrência");
                bus.emitir("caucao_liberada", props("reservation_id", c.reservaId, "valor", c.valor));
            }
        }
    }

    private void mudar(Caucao c, StatusCaucao para, String ator, String motivo) {
        audit.registrar(EntidadeAuditoria.CAUCAO, c.reservaId, c.status == para ? null : c.status, para, ator, motivo);
        c.status = para;
    }
}
