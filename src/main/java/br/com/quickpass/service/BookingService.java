package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.*;
import br.com.quickpass.domain.*;
import br.com.quickpass.web.Dtos.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static br.com.quickpass.common.Datas.diasEntre;
import static br.com.quickpass.common.Datas.sobrepoe;
import static br.com.quickpass.common.Util.*;

/**
 * Orquestração da reserva (RF12). Cada transição é validada, auditada e emite o evento
 * correspondente; efeitos financeiros passam pelo PaymentGateway e pelo DepositService.
 * Executa sempre sob o lock global do Store (ver AuthInterceptor / SchedulerService).
 */
@Service
public class BookingService {
    private final Store store;
    private final PricingService pricing;
    private final AppConfigService config;
    private final ClockService clock;
    private final AuditService audit;
    private final EventBus bus;
    private final UsuarioService usuarios;
    private final PaymentGateway gateway;
    private final DepositService deposit;
    private final PayoutService payout;
    private final AgendaService agenda;
    private final ReviewService reviews;
    private final ExperimentService experimento;
    private final ReferralService referral;
    private final StorageService storage;

    public BookingService(Store store, PricingService pricing, AppConfigService config, ClockService clock, AuditService audit, EventBus bus,
                          UsuarioService usuarios, PaymentGateway gateway, DepositService deposit, PayoutService payout, AgendaService agenda,
                          ReviewService reviews, ExperimentService experimento, ReferralService referral, StorageService storage) {
        this.store = store;
        this.pricing = pricing;
        this.config = config;
        this.clock = clock;
        this.audit = audit;
        this.bus = bus;
        this.usuarios = usuarios;
        this.gateway = gateway;
        this.deposit = deposit;
        this.payout = payout;
        this.agenda = agenda;
        this.reviews = reviews;
        this.experimento = experimento;
        this.referral = referral;
        this.storage = storage;
    }

    // ───────────── consultas ─────────────

    public Reserva obrigatoria(String id) {
        Reserva r = store.reservas.get(id);
        if (r == null) throw ApiException.naoEncontrado("Reserva não encontrada: " + id);
        return r;
    }

    public Veiculo veiculoDa(Reserva r) {
        return store.veiculos.get(r.veiculoId);
    }

    public boolean ehLocatario(Usuario u, Reserva r) {
        return u.id.equals(r.locatarioId);
    }

    public boolean ehProprietario(Usuario u, Reserva r) {
        Veiculo v = veiculoDa(r);
        return v != null && v.proprietarioId.equals(u.id);
    }

    /** Reserva visível para locatário, proprietário do veículo ou admin; senão 403. */
    public Reserva visivelPara(Usuario u, String id) {
        Reserva r = obrigatoria(id);
        if (!ehLocatario(u, r) && !ehProprietario(u, r) && !u.temPapel(Papel.ADMIN)) throw ApiException.proibido("Você não participa desta reserva.");
        return r;
    }

    private Reserva doLocatario(Usuario u, String id) {
        Reserva r = obrigatoria(id);
        if (!ehLocatario(u, r)) throw ApiException.proibido("Apenas o locatário da reserva pode executar esta ação.");
        return r;
    }

    private Reserva doProprietario(Usuario u, String id) {
        Reserva r = obrigatoria(id);
        if (!ehProprietario(u, r)) throw ApiException.proibido("Apenas o proprietário do veículo pode executar esta ação.");
        return r;
    }

    public List<Reserva> doLocatario(Usuario u) {
        return store.reservas.values().stream().filter(r -> r.locatarioId.equals(u.id))
                .sorted(Comparator.comparing((Reserva r) -> r.criadaEm).reversed()).toList();
    }

    public List<Reserva> doProprietario(Usuario u) {
        return store.reservas.values().stream().filter(r -> ehProprietario(u, r))
                .sorted(Comparator.comparing((Reserva r) -> r.criadaEm).reversed()).toList();
    }

    public Pagamento pagamentoDaReserva(String id) {
        return store.pagamentos.get(id);
    }

    /** Pagamento e caução confirmados (libera o endereço exato — RNF02). */
    public boolean pagamentoConfirmado(String id) {
        Reserva r = store.reservas.get(id);
        Pagamento p = store.pagamentos.get(id);
        return r != null && StatusReserva.PAGAS.contains(r.status) && p != null
                && (p.status == StatusPagamento.FINALIZADO || p.status == StatusPagamento.ESTORNADO_PARCIAL);
    }

    /** Endereço exato: só ao locatário com KYC aprovado e pagamento confirmado; o proprietário sempre vê o seu. */
    public String enderecoExato(Usuario u, String reservaId) {
        Reserva r = visivelPara(u, reservaId);
        Veiculo v = veiculoDa(r);
        if (ehProprietario(u, r)) return v.enderecoExato;
        if (!ehLocatario(u, r)) return null;
        return usuarios.verificarExpiracao(u) && pagamentoConfirmado(reservaId) ? v.enderecoExato : null;
    }

    // ───────────── solicitação ─────────────

    /**
     * Cria a solicitação. Sem KYC aprovado, a reserva fica em AGUARDANDO_KYC com hold de
     * HOLD_KYC_MINUTOS (BDD 9.1); o cliente deve levar o usuário ao KYC e chamar continuarAposKyc.
     */
    public Reserva solicitar(Usuario loc, SolicitarReq req) {
        if (!loc.temPapel(Papel.LOCATARIO)) throw ApiException.proibido("Ative o papel de locatário para reservar.");
        Veiculo v = store.veiculos.get(req.veiculoId());
        if (v == null) throw ApiException.naoEncontrado("Veículo não encontrado.");
        if (v.status != StatusVeiculo.APROVADO) throw ApiException.regra("Veículo indisponível para locação.");
        if (v.proprietarioId.equals(loc.id)) throw ApiException.regra("Você não pode alugar o próprio veículo.");
        if (!req.fim().isAfter(req.inicio())) throw ApiException.regra("A devolução deve ser depois da retirada.");
        if (req.inicio().isBefore(clock.hoje())) throw ApiException.regra("A retirada não pode ser no passado.");
        if (agenda.temConflito(v.id, req.inicio(), req.fim(), null)) throw ApiException.conflito("Veículo indisponível no período: há reserva ou hold ativo.");
        if (!agenda.diasLiberados(v.id, diasEntre(req.inicio(), req.fim()))) throw ApiException.conflito("O proprietário não liberou todas as datas do período.");
        agenda.validarPreferencias(v.id, req.inicio(), req.fim());

        boolean kycOk = usuarios.verificarExpiracao(loc);
        Instant prazoAprovacao = clock.maisHoras(config.getInt(Parametro.PRAZO_APROVACAO_RESERVA_HORAS));
        Reserva r = new Reserva();
        r.id = "QP-" + store.proximo("reserva", 2400);
        r.veiculoId = v.id;
        r.locatarioId = loc.id;
        r.inicio = req.inicio();
        r.fim = req.fim();
        r.protecao = req.protecao();
        r.status = kycOk ? StatusReserva.SOLICITADA : StatusReserva.AGUARDANDO_KYC;
        r.valores = pricing.calcular(v.diaria, req.protecao(), req.inicio(), req.fim());
        r.finalidade = req.finalidade();
        r.origem = req.origem() == null || req.origem().isBlank() ? "busca" : req.origem();
        r.criadaEm = clock.now();
        r.holdExpiraEm = kycOk ? prazoAprovacao : clock.maisMinutos(config.getInt(Parametro.HOLD_KYC_MINUTOS));
        r.aprovacaoExpiraEm = kycOk ? prazoAprovacao : null;
        store.reservas.put(r.id, r);
        audit.registrar(EntidadeAuditoria.RESERVA, r.id, null, r.status, loc.id, null);
        if (kycOk) aoSolicitar(r);
        return r;
    }

    /** Depois que o KYC é aprovado, a reserva retida segue para a fila do proprietário. */
    public Reserva continuarAposKyc(Usuario loc, String id) {
        Reserva r = doLocatario(loc, id);
        if (r.status != StatusReserva.AGUARDANDO_KYC) throw ApiException.regra("Reserva não está aguardando KYC.");
        if (clock.passou(r.holdExpiraEm)) {
            expirar(r, "hold de KYC vencido");
            throw ApiException.regra("O prazo de reserva das datas terminou.");
        }
        if (!usuarios.verificarExpiracao(loc)) throw ApiException.regra("KYC ainda não aprovado.");
        Instant prazo = clock.maisHoras(config.getInt(Parametro.PRAZO_APROVACAO_RESERVA_HORAS));
        r.holdExpiraEm = prazo;
        r.aprovacaoExpiraEm = prazo;
        mover(r, StatusReserva.SOLICITADA, loc.id, null);
        aoSolicitar(r);
        return r;
    }

    private void aoSolicitar(Reserva r) {
        bus.emitir("reserva_solicitada", props("reservation_id", r.id, "vehicle_id", r.veiculoId, "total_amount", r.valores.total(), "origem", r.origem));
        bus.emitir("solicitacao_recebida", props("reservation_id", r.id, "vehicle_id", r.veiculoId));
        // Aprovação automática: investimento do proprietário (RF12/RF17), só p/ locatários bem avaliados.
        Usuario loc = store.usuarios.get(r.locatarioId);
        Double media = reviews.reputacaoDoLocatario(r.locatarioId).media();
        double nota = media != null ? media : (loc == null ? 0 : loc.nota);
        if (agenda.aceiteAutomatico(r.veiculoId) && nota >= config.get(Parametro.NOTA_MINIMA_APROVACAO_AUTOMATICA)) {
            aprovarInterno(r, "sistema", "aprovação automática");
        }
    }

    // ───────────── resposta do proprietário ─────────────

    public Reserva aprovar(Usuario dono, String id) {
        Reserva r = doProprietario(dono, id);
        aprovarInterno(r, dono.id, null);
        return r;
    }

    private void aprovarInterno(Reserva r, String ator, String motivo) {
        r.holdExpiraEm = clock.maisHoras(config.getInt(Parametro.PRAZO_PAGAMENTO_HORAS));
        mover(r, StatusReserva.APROVADA, ator, motivo);
        bus.emitir("reserva_aprovada", props("reservation_id", r.id, "ator", ator));
    }

    public Reserva rejeitar(Usuario dono, String id, String motivo) {
        Reserva r = doProprietario(dono, id);
        r.holdExpiraEm = null;
        mover(r, StatusReserva.REJEITADA, dono.id, motivo);
        bus.emitir("reserva_rejeitada", props("reservation_id", r.id, "ator", dono.id, "motivo", motivo));
        return r;
    }

    // ───────────── pagamento + caução → confirmada ─────────────

    /**
     * Cobra a diária e pré-autoriza a caução. Só confirma se AMBOS passarem; qualquer falha vai a
     * FALHA_PAGAMENTO, libera o hold e (se já cobrou) estorna.
     */
    public Reserva pagar(Usuario loc, String id, PagarReq req) {
        Reserva r = doLocatario(loc, id);
        if (r.status != StatusReserva.APROVADA) throw ApiException.regra("Só é possível pagar uma reserva aprovada.");
        if (clock.passou(r.holdExpiraEm)) {
            expirar(r, "prazo de pagamento vencido");
            throw ApiException.regra("O prazo para pagamento terminou.");
        }
        boolean sobreposta = store.reservas.values().stream().anyMatch(o -> !o.id.equals(r.id) && o.veiculoId.equals(r.veiculoId)
                && (o.status == StatusReserva.CONFIRMADA || o.status == StatusReserva.EM_ANDAMENTO) && sobrepoe(r.inicio, r.fim, o.inicio, o.fim));
        if (sobreposta) throw ApiException.conflito("Há outra reserva confirmada sobreposta.");

        String cartao = req == null || req.numeroCartao() == null ? null : soDigitos(req.numeroCartao());
        if (cartao != null && !cartao.isEmpty()) referral.registrarCartao(loc.id, sha256(cartao));
        Valores v = r.valores;
        Pagamento p = new Pagamento();
        p.reservaId = id;
        p.valorDiaria = v.diaria();
        p.taxaPlataforma = v.taxa();
        p.total = v.total();
        p.status = StatusPagamento.PENDENTE;
        store.pagamentos.put(id, p);

        var cobranca = gateway.cobrar(id, v.total(), cartao);
        if (!cobranca.ok()) {
            falharPagamento(r, p, cobranca.motivo(), cobranca.motivo());
            return r;
        }
        if (!deposit.preAutorizar(id, v.caucao(), r.fim, cartao)) {
            gateway.estornar(id, v.total());
            falharPagamento(r, p, "caução não pré-autorizada", "falha na pré-autorização da caução");
            return r;
        }
        // RF09: isenção da taxa na 1ª locação de novos proprietários (grupo tratamento do experimento).
        String donoId = veiculoDa(r).proprietarioId;
        p.desconto = experimento.avaliarDesconto(donoId, v.taxa(), proprietarioTemConcluida(donoId), id);
        p.status = StatusPagamento.FINALIZADO;
        p.pagoEm = clock.now();
        r.holdExpiraEm = null;
        mover(r, StatusReserva.CONFIRMADA, loc.id, null);
        bus.emitir("pagamento_finalizado", props("reservation_id", id, "valor", v.total(), "taxa_plataforma", v.taxa(), "desconto", p.desconto));
        return r;
    }

    private void falharPagamento(Reserva r, Pagamento p, String motivoEvento, String motivoAuditoria) {
        p.status = StatusPagamento.FALHOU;
        bus.emitir("pagamento_falhou", props("reservation_id", r.id, "valor", r.valores.total(), "taxa_plataforma", r.valores.taxa(), "desconto", 0, "motivo", motivoEvento));
        r.holdExpiraEm = null;
        mover(r, StatusReserva.FALHA_PAGAMENTO, "sistema", motivoAuditoria);
    }

    private boolean proprietarioTemConcluida(String ownerId) {
        return store.reservas.values().stream().anyMatch(x -> x.status == StatusReserva.CONCLUIDA && veiculoDa(x).proprietarioId.equals(ownerId));
    }

    // ───────────── cancelamento ─────────────

    /**
     * Cancela e aplica a política de estorno por antecedência (config; ponto em aberto §14.5).
     * Cancelamento pelo proprietário ou admin reembolsa 100%.
     */
    public Reserva cancelar(Usuario u, String id, String motivo) {
        Reserva r = visivelPara(u, id);
        boolean paga = r.status == StatusReserva.CONFIRMADA;
        r.holdExpiraEm = null;
        mover(r, StatusReserva.CANCELADA, u.id, motivo);
        bus.emitir("reserva_cancelada", props("reservation_id", id, "ator", u.id, "motivo", motivo));
        if (!paga) return r;
        Pagamento p = store.pagamentos.get(id);
        double horasAte = Duration.between(clock.now(), r.inicio.atStartOfDay(ClockService.ZONA).toInstant()).toMinutes() / 60.0;
        double pct = !ehLocatario(u, r) || horasAte >= config.get(Parametro.CANCELAMENTO_REEMBOLSO_TOTAL_HORAS) ? 100 : config.get(Parametro.CANCELAMENTO_REEMBOLSO_PARCIAL_PCT);
        double valor = r2(p.total * pct / 100);
        gateway.estornar(id, valor);
        p.estornado = valor;
        StatusPagamento novo = pct == 100 ? StatusPagamento.ESTORNADO_TOTAL : StatusPagamento.ESTORNADO_PARCIAL;
        audit.registrar(EntidadeAuditoria.PAGAMENTO, id, p.status, novo, u.id, "política de cancelamento: %.0f%% de reembolso".formatted(pct));
        p.status = novo;
        deposit.liberarPorCancelamento(id);
        return r;
    }

    // ───────────── vistorias (RF03) ─────────────

    public static final List<FotoChave> FOTOS_OBRIGATORIAS = List.of(FotoChave.values());

    private String chave(String reservaId, TipoVistoria tipo) {
        return reservaId + ":" + tipo;
    }

    /** Rascunho/vistoria da reserva (criada vazia se não existir). Visível às duas partes. */
    public Vistoria vistoria(Usuario u, String reservaId, TipoVistoria tipo) {
        Reserva r = visivelPara(u, reservaId);
        return store.vistorias.computeIfAbsent(chave(r.id, tipo), k -> {
            Vistoria v = new Vistoria();
            v.reservaId = r.id;
            v.tipo = tipo;
            v.criadaEm = clock.now();
            return v;
        });
    }

    private Vistoria editavel(Usuario u, String reservaId, TipoVistoria tipo) {
        Reserva r = visivelPara(u, reservaId);
        if (u.temPapel(Papel.ADMIN) && !ehLocatario(u, r) && !ehProprietario(u, r)) throw ApiException.proibido("Apenas as partes da locação alteram a vistoria.");
        Vistoria v = vistoria(u, reservaId, tipo);
        if (v.concluida) throw ApiException.conflito("A vistoria já foi concluída.");
        return v;
    }

    /** Recebe a imagem da vistoria; só depois de gravada ela passa a contar como confirmada pelo servidor. */
    public Vistoria enviarFoto(Usuario u, String reservaId, TipoVistoria tipo, FotoChave chave, MultipartFile arquivo, String localAproximada) {
        Vistoria v = editavel(u, reservaId, tipo);
        Arquivo salvo = storage.salvar(arquivo, CategoriaArquivo.VISTORIA_FOTO, u.id, reservaId, false);
        FotoVistoria anterior = v.fotos.get(chave);
        if (anterior != null && anterior.arquivoId != null) storage.remover(anterior.arquivoId);
        FotoVistoria f = new FotoVistoria();
        f.arquivoId = salvo.id;
        f.enviada = true;
        f.tiradaEm = clock.now();
        f.localAproximada = localAproximada;
        v.fotos.put(chave, f);
        return v;
    }

    public Vistoria atualizarDados(Usuario u, String reservaId, TipoVistoria tipo, VistoriaDadosReq dados) {
        Vistoria v = editavel(u, reservaId, tipo);
        if (dados.km() != null) v.km = dados.km();
        if (dados.combustivel() != null) v.combustivel = dados.combustivel();
        if (dados.observacoes() != null) v.observacoes = dados.observacoes();
        return v;
    }

    /** Registra o aceite da parte do usuário (locatário ou proprietário). */
    public Vistoria aceitar(Usuario u, String reservaId, TipoVistoria tipo) {
        Vistoria v = editavel(u, reservaId, tipo);
        Reserva r = obrigatoria(reservaId);
        if (ehLocatario(u, r)) v.aceiteLocatario = true;
        else v.aceiteProprietario = true;
        return v;
    }

    /** O check-in só é aceito dentro da janela da reserva (JANELA_CHECKIN_MINUTOS antes da retirada até o fim). */
    public boolean dentroDaJanelaCheckin(Reserva r) {
        Instant abre = r.inicio.atStartOfDay(ClockService.ZONA).toInstant().minusSeconds(config.getInt(Parametro.JANELA_CHECKIN_MINUTOS) * 60L);
        Instant fecha = r.fim.plusDays(1).atStartOfDay(ClockService.ZONA).toInstant();
        Instant t = clock.now();
        return !t.isBefore(abre) && t.isBefore(fecha);
    }

    /**
     * Conclui a vistoria. Exige as 7 fotos confirmadas E o aceite das duas partes; só então o
     * status muda. No check-out, {@code avaria} leva a EM_DISPUTA; caso contrário a CONCLUIDA e a
     * caução entra na fila de liberação automática.
     */
    public Reserva concluirVistoria(Usuario u, String reservaId, TipoVistoria tipo, boolean avaria) {
        Vistoria vis = editavel(u, reservaId, tipo);
        Reserva r = obrigatoria(reservaId);
        int obrigatorias = config.getInt(Parametro.FOTOS_VISTORIA_OBRIGATORIAS);
        if (vis.fotosEnviadas() < obrigatorias) throw ApiException.regra("Faltam fotos: são obrigatórias %d confirmadas.".formatted(obrigatorias));
        if (!vis.aceiteLocatario || !vis.aceiteProprietario) throw ApiException.regra("É necessário o aceite do locatário e do proprietário.");
        if (tipo == TipoVistoria.CHECKIN) {
            if (r.status != StatusReserva.CONFIRMADA) throw ApiException.regra("Check-in só é possível em reserva confirmada.");
            if (!dentroDaJanelaCheckin(r)) throw ApiException.regra("Fora da janela de check-in da reserva.");
            vis.concluida = true;
            mover(r, StatusReserva.EM_ANDAMENTO, u.id, null);
            boolean loc = vis.fotos.values().stream().anyMatch(f -> f.localAproximada != null);
            bus.emitir("checkin_concluido", props("reservation_id", r.id, "qtd_fotos", vis.fotosEnviadas(), "status_localizacao", loc ? "registrada" : "nao_informada"));
            garantirPrevisto(r);
            return r;
        }
        if (r.status != StatusReserva.EM_ANDAMENTO) throw ApiException.regra("Check-out só é possível em reserva em andamento.");
        garantirPrevisto(r);
        vis.concluida = true;
        r.checkoutEm = clock.now();
        mover(r, avaria ? StatusReserva.EM_DISPUTA : StatusReserva.CONCLUIDA, u.id, null);
        long horas = Math.max(0, Duration.between(r.inicio.atStartOfDay(ClockService.ZONA).toInstant(), clock.now()).toHours());
        bus.emitir("checkout_concluido", props("reservation_id", r.id, "duracao_horas", horas, "avaria_flag", avaria));
        if (!avaria) deposit.agendarLiberacao(r.id);
        return r;
    }

    private void garantirPrevisto(Reserva r) {
        Veiculo v = veiculoDa(r);
        Pagamento p = store.pagamentos.get(r.id);
        payout.registrarPrevisto(r.id, r.veiculoId, v.proprietarioId, v.titulo, pricing.formatarPeriodo(r.inicio, r.fim),
                r.valores.subtotal(), r.valores.taxa(), p == null ? 0 : p.desconto);
    }

    // ───────────── conclusão pós-disputa e repasse ─────────────

    /** Chamado pelo DisputeService após decisão registrada. */
    public void concluirAposDisputa(String id, String ator) {
        mover(obrigatoria(id), StatusReserva.CONCLUIDA, ator, "disputa decidida");
    }

    /** Repasse só após CONCLUIDA + caução resolvida (RF12). Idempotente; também emite transacao_concluida. */
    public void processarRepasses() {
        for (Reserva r : new ArrayList<>(store.reservas.values())) {
            if (r.status != StatusReserva.CONCLUIDA || !deposit.resolvida(r.id)) continue;
            Repasse rep = payout.daReserva(r.id);
            if (rep == null || rep.status == StatusRepasse.PAGO) continue;
            payout.pagar(r.id);
            Pagamento p = store.pagamentos.get(r.id);
            String donoId = veiculoDa(r).proprietarioId;
            bus.emitir("repasse_realizado", props("reservation_id", r.id, "owner_id", donoId, "valor", rep.liquido));
            bus.emitir("transacao_concluida", props("reservation_id", r.id, "gmv", r.valores.total(), "taxa_plataforma", r.valores.taxa(),
                    "desconto", p == null ? 0 : p.desconto, "estornado", p != null && p.estornado >= p.total,
                    "locatario_id", r.locatarioId, "owner_id", donoId));
            // Indicação qualifica quando o convidado conclui E paga a 1ª locação.
            Instant ref = r.concluidaEm == null ? Instant.EPOCH : r.concluidaEm;
            long anteriores = store.reservas.values().stream().filter(x -> x.locatarioId.equals(r.locatarioId) && x.status == StatusReserva.CONCLUIDA
                    && !x.id.equals(r.id) && !(x.concluidaEm == null ? Instant.EPOCH : x.concluidaEm).isAfter(ref)).count();
            if (anteriores == 0 && p != null && (p.status == StatusPagamento.FINALIZADO || p.status == StatusPagamento.ESTORNADO_PARCIAL)) {
                referral.qualificar(r.locatarioId);
            }
        }
    }

    // ───────────── jobs ─────────────

    /** Job: holds vencidos (KYC, resposta do proprietário, pagamento) viram EXPIRADA e liberam a agenda. */
    public void expirarPendentes() {
        for (Reserva r : new ArrayList<>(store.reservas.values())) {
            if (StatusReserva.HOLDS.contains(r.status) && clock.passou(r.holdExpiraEm)) {
                expirar(r, switch (r.status) {
                    case AGUARDANDO_KYC -> "hold de KYC vencido";
                    case SOLICITADA -> "sem resposta do proprietário no prazo";
                    default -> "prazo de pagamento vencido";
                });
            }
        }
    }

    private void expirar(Reserva r, String motivo) {
        r.holdExpiraEm = null;
        mover(r, StatusReserva.EXPIRADA, "sistema", motivo);
        bus.emitir("reserva_expirada", props("reservation_id", r.id, "ator", "sistema", "motivo", motivo));
    }

    // ───────────── solicitações do proprietário ─────────────

    private static final Map<StatusReserva, StatusSolicitacao> STATUS_SOLICITACAO = Map.of(
            StatusReserva.SOLICITADA, StatusSolicitacao.PENDENTE,
            StatusReserva.APROVADA, StatusSolicitacao.ACEITA,
            StatusReserva.REJEITADA, StatusSolicitacao.RECUSADA,
            StatusReserva.EXPIRADA, StatusSolicitacao.EXPIRADA);

    /** Fila do proprietário, derivada das reservas (fonte única da verdade — RF12). */
    public List<SolicitacaoView> solicitacoes(Usuario dono) {
        return doProprietario(dono).stream().filter(r -> STATUS_SOLICITACAO.containsKey(r.status)).map(this::paraSolicitacao).toList();
    }

    private SolicitacaoView paraSolicitacao(Reserva r) {
        Usuario loc = store.usuarios.get(r.locatarioId);
        String nome = loc == null ? "Locatário" : abreviar(loc.nome);
        Double media = reviews.reputacaoDoLocatario(r.locatarioId).media();
        int locacoes = (int) store.reservas.values().stream().filter(x -> x.locatarioId.equals(r.locatarioId) && x.status == StatusReserva.CONCLUIDA).count();
        var resumo = new LocatarioResumo(nome, loc == null ? 0 : loc.cnhAnos, media != null ? media : (loc == null ? 0 : loc.nota), locacoes);
        Instant expira = r.aprovacaoExpiraEm != null ? r.aprovacaoExpiraEm : (r.holdExpiraEm != null ? r.holdExpiraEm : r.criadaEm);
        return new SolicitacaoView(r.id, r.id, r.veiculoId, veiculoDa(r).titulo, resumo, r.inicio, r.fim, r.valores.diaria(),
                r.finalidade == null || r.finalidade.isBlank() ? "Não informada" : r.finalidade, STATUS_SOLICITACAO.get(r.status), expira);
    }

    private String abreviar(String nome) {
        String[] p = nome.trim().split("\\s+");
        return p.length > 1 ? p[0] + " " + p[p.length - 1].charAt(0) + "." : p[0];
    }

    // ───────────── núcleo da máquina de estados ─────────────

    /** Única porta de mudança de status: valida a tabela de transições, audita e persiste. */
    public void mover(Reserva r, StatusReserva para, String ator, String motivo) {
        if (!r.status.podeIrPara(para)) throw ApiException.conflito("Transição inválida de reserva: %s → %s".formatted(r.status, para));
        StatusReserva de = r.status;
        r.status = para;
        if (para == StatusReserva.CONCLUIDA) {
            r.concluidaEm = clock.now();
            veiculoDa(r).locacoes++;
        }
        audit.registrar(EntidadeAuditoria.RESERVA, r.id, de, para, ator, motivo);
    }
}
