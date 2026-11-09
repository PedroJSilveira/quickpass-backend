package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.*;
import br.com.quickpass.service.*;
import br.com.quickpass.web.Dtos.SolicitacaoView;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Painel, solicitações recebidas e ganhos do proprietário. */
@RestController
@RequestMapping("/api/proprietario")
public class ProprietarioController {
    private final BookingService booking;
    private final CatalogService catalog;
    private final PayoutService payout;
    private final ReviewService reviews;
    private final HealthScoreService healthScore;

    public ProprietarioController(BookingService booking, CatalogService catalog, PayoutService payout, ReviewService reviews, HealthScoreService healthScore) {
        this.booking = booking;
        this.catalog = catalog;
        this.payout = payout;
        this.reviews = reviews;
        this.healthScore = healthScore;
    }

    @GetMapping("/painel")
    public Map<String, Object> painel() {
        Usuario u = AuthContext.exigir(Papel.PROPRIETARIO);
        var veiculos = catalog.doProprietario(u.id);
        var reservas = booking.doProprietario(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("veiculosAtivos", veiculos.stream().filter(v -> v.status == StatusVeiculo.APROVADO).count());
        m.put("veiculosEmAnalise", veiculos.stream().filter(v -> v.status == StatusVeiculo.EM_ANALISE).count());
        m.put("solicitacoesPendentes", booking.solicitacoes(u).stream().filter(s -> s.status() == StatusSolicitacao.PENDENTE).count());
        m.put("reservasEmAndamento", reservas.stream().filter(r -> r.status == StatusReserva.EM_ANDAMENTO).count());
        m.put("proximasReservas", reservas.stream().filter(r -> r.status == StatusReserva.CONFIRMADA).toList());
        m.put("ganhosMes", payout.liquidoMesAtual(u.id));
        m.put("saldoPrevisto", payout.saldoPrevisto(u.id));
        m.put("reputacao", reviews.reputacaoDoProprietario(u.id));
        m.put("healthScore", healthScore.calcular(u.id, Papel.PROPRIETARIO));
        return m;
    }

    @GetMapping("/solicitacoes")
    public List<SolicitacaoView> solicitacoes(@RequestParam(required = false) StatusSolicitacao status) {
        return booking.solicitacoes(AuthContext.exigir(Papel.PROPRIETARIO)).stream().filter(s -> status == null || s.status() == status).toList();
    }

    @GetMapping("/ganhos")
    public Map<String, Object> ganhos() {
        Usuario u = AuthContext.exigir(Papel.PROPRIETARIO);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("liquidoMesAtual", payout.liquidoMesAtual(u.id));
        m.put("saldoPrevisto", payout.saldoPrevisto(u.id));
        m.put("repasses", payout.doProprietario(u.id));
        return m;
    }
}
