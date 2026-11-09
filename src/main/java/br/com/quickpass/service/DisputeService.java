package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.*;
import br.com.quickpass.domain.*;
import br.com.quickpass.web.Dtos.*;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

import static br.com.quickpass.common.Util.props;

/**
 * Disputa de avaria com contraditório (RF04, §4.4):
 * ABERTA → AGUARDANDO_CONTESTACAO → (CONTESTADA | NAO_CONTESTADA_NO_PRAZO) → EM_ANALISE → DECIDIDA.
 * A decisão (mediador/admin) é o único caminho para capturar a caução.
 */
@Service
public class DisputeService {
    private final Store store;
    private final BookingService booking;
    private final DepositService deposit;
    private final AppConfigService config;
    private final ClockService clock;
    private final EventBus bus;
    private final AuditService audit;
    private final NotificationService notificacoes;

    public DisputeService(Store store, BookingService booking, DepositService deposit, AppConfigService config, ClockService clock,
                          EventBus bus, AuditService audit, NotificationService notificacoes) {
        this.store = store;
        this.booking = booking;
        this.deposit = deposit;
        this.config = config;
        this.clock = clock;
        this.bus = bus;
        this.audit = audit;
        this.notificacoes = notificacoes;
    }

    public Disputa obrigatoria(String id) {
        Disputa d = store.disputas.get(id);
        if (d == null) throw ApiException.naoEncontrado("Disputa não encontrada: " + id);
        return d;
    }

    public Disputa daReserva(String reservaId) {
        return store.disputas.values().stream().filter(d -> d.reservaId.equals(reservaId)).findFirst().orElse(null);
    }

    public List<Disputa> todas(boolean somenteAbertas) {
        return store.disputas.values().stream().filter(d -> !somenteAbertas || d.status != StatusDisputa.DECIDIDA).toList();
    }

    /** Disputas das reservas em que o usuário é locatário ou proprietário. */
    public List<Disputa> doUsuario(Usuario u) {
        return store.disputas.values().stream().filter(d -> {
            Reserva r = store.reservas.get(d.reservaId);
            return booking.ehLocatario(u, r) || booking.ehProprietario(u, r);
        }).sorted(Comparator.comparing((Disputa d) -> d.avaria.informadaEm()).reversed()).toList();
    }

    /** Proprietário informa a avaria (DamageReport): exige descrição; retém a caução, não captura. */
    public Disputa informarAvaria(Usuario dono, String reservaId, AvariaReq req) {
        Reserva r = booking.obrigatoria(reservaId);
        if (!booking.ehProprietario(dono, r)) throw ApiException.proibido("Apenas o proprietário do veículo informa a avaria.");
        if (r.status != StatusReserva.EM_DISPUTA) throw ApiException.regra("Avaria só pode ser informada em reserva em disputa (check-out com avaria).");
        if (req.descricao().isBlank() || req.qtdFotos() < 1) throw ApiException.regra("Informe descrição e ao menos uma foto da avaria.");
        if (daReserva(reservaId) != null) throw ApiException.conflito("Já existe disputa para esta reserva.");
        Disputa d = new Disputa();
        d.id = "D-" + store.proximo("disputa", 1);
        d.reservaId = reservaId;
        d.status = StatusDisputa.ABERTA;
        d.avaria = new Disputa.Avaria(req.descricao().trim(), req.qtdFotos(), clock.now(), req.valorEstimado());
        d.contestarAte = clock.maisHoras(config.getInt(Parametro.PRAZO_CONTESTACAO_HORAS));
        store.disputas.put(d.id, d);
        audit.registrar(EntidadeAuditoria.DISPUTA, d.id, null, StatusDisputa.ABERTA, dono.id, null);
        deposit.reter(reservaId); // retida, NÃO capturada
        mudar(d, StatusDisputa.AGUARDANDO_CONTESTACAO, "sistema", null);
        bus.emitir("avaria_informada", props("reservation_id", reservaId, "dispute_id", d.id));
        // O locatário é sempre notificado (transacional) e pode contestar.
        notificacoes.enviarTransacional(r.locatarioId, TipoNotificacao.AVARIA_INFORMADA, "Avaria informada",
                "O proprietário informou uma avaria na locação %s. Você pode contestar até o prazo.".formatted(reservaId),
                "/locacoes/" + reservaId + "/contestacao", props("dispute_id", d.id));
        return d;
    }

    public Disputa contestar(Usuario loc, String disputaId, ContestacaoReq req) {
        Disputa d = obrigatoria(disputaId);
        Reserva r = booking.obrigatoria(d.reservaId);
        if (!booking.ehLocatario(loc, r)) throw ApiException.proibido("Apenas o locatário contesta a avaria.");
        if (d.status != StatusDisputa.AGUARDANDO_CONTESTACAO) throw ApiException.regra("A disputa não está aberta para contestação.");
        if (clock.passou(d.contestarAte)) throw ApiException.regra("O prazo de contestação terminou.");
        d.contestacao = new Disputa.Contestacao(req.texto().trim(), req.qtdEvidencias(), clock.now());
        mudar(d, StatusDisputa.CONTESTADA, loc.id, null);
        bus.emitir("avaria_contestada", props("reservation_id", d.reservaId, "dispute_id", disputaId));
        return d;
    }

    /** Mediador assume a análise (somente após contestação ou fim do prazo). */
    public Disputa assumirAnalise(String disputaId, Usuario mediador) {
        Disputa d = obrigatoria(disputaId);
        if (d.status != StatusDisputa.CONTESTADA && d.status != StatusDisputa.NAO_CONTESTADA_NO_PRAZO) {
            throw ApiException.regra("Aguarde a contestação ou o fim do prazo.");
        }
        mudar(d, StatusDisputa.EM_ANALISE, mediador.id, null);
        return d;
    }

    /** Registra a decisão, aplica na caução e conclui a reserva. */
    public Disputa decidir(String disputaId, DecisaoReq req, Usuario mediador) {
        Disputa d = obrigatoria(disputaId);
        if (d.status != StatusDisputa.EM_ANALISE) throw ApiException.regra("A disputa precisa estar em análise para ser decidida.");
        Caucao caucao = deposit.porReserva(d.reservaId);
        if (caucao == null) throw ApiException.regra("Caução não encontrada.");
        double valor = switch (req.tipo()) {
            case CAPTURA_TOTAL -> caucao.valor;
            case LIBERACAO -> 0;
            case CAPTURA_PARCIAL -> req.valor();
        };
        var decisao = new DecisaoDisputa(req.tipo(), valor, req.justificativa(), mediador.id, clock.now());
        deposit.aplicarDecisao(d.reservaId, decisao); // valida e falha se a decisão for inválida
        d.decisao = decisao;
        mudar(d, StatusDisputa.DECIDIDA, mediador.id, req.justificativa());
        bus.emitir("disputa_decidida", props("reservation_id", d.reservaId, "dispute_id", disputaId, "decisao", decisao.tipo().name().toLowerCase()));
        booking.concluirAposDisputa(d.reservaId, mediador.id);
        return d;
    }

    /** Job: sem contestação no prazo → NAO_CONTESTADA_NO_PRAZO (segue para análise do mediador). */
    public void encerrarPrazosDeContestacao() {
        for (Disputa d : store.disputas.values()) {
            if (d.status == StatusDisputa.AGUARDANDO_CONTESTACAO && clock.passou(d.contestarAte)) {
                mudar(d, StatusDisputa.NAO_CONTESTADA_NO_PRAZO, "sistema", null);
            }
        }
    }

    private void mudar(Disputa d, StatusDisputa para, String ator, String motivo) {
        audit.registrar(EntidadeAuditoria.DISPUTA, d.id, d.status, para, ator, motivo);
        d.status = para;
    }
}
