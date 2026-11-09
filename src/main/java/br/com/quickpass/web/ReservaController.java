package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.*;
import br.com.quickpass.service.*;
import br.com.quickpass.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Reservas: solicitar, aprovar, pagar, cancelar, vistorias (check-in/out) e avaliações. */
@RestController
@RequestMapping("/api/reservas")
public class ReservaController {
    public record ReservaDetalhe(Reserva reserva, VeiculoPublico veiculo, Pagamento pagamento, Caucao caucao, String enderecoExato,
                                 Disputa disputa, List<Avaliacao> avaliacoes, boolean dentroDaJanelaCheckin) {
    }

    private final BookingService booking;
    private final CatalogService catalog;
    private final DepositService deposit;
    private final DisputeService disputes;
    private final ReviewService reviews;

    public ReservaController(BookingService booking, CatalogService catalog, DepositService deposit, DisputeService disputes, ReviewService reviews) {
        this.booking = booking;
        this.catalog = catalog;
        this.deposit = deposit;
        this.disputes = disputes;
        this.reviews = reviews;
    }

    /** Lista as reservas do usuário como {@code locatario} (padrão) ou {@code proprietario}. */
    @GetMapping
    public List<Reserva> listar(@RequestParam(defaultValue = "locatario") Papel como) {
        Usuario u = AuthContext.exigir(como);
        return como == Papel.PROPRIETARIO ? booking.doProprietario(u) : como == Papel.LOCATARIO ? booking.doLocatario(u) : List.of();
    }

    @GetMapping("/{id}")
    public ReservaDetalhe detalhe(@PathVariable String id) {
        Usuario u = AuthContext.usuario();
        Reserva r = booking.visivelPara(u, id);
        return new ReservaDetalhe(r, catalog.publico(booking.veiculoDa(r)), booking.pagamentoDaReserva(id), deposit.porReserva(id),
                booking.enderecoExato(u, id), disputes.daReserva(id), reviews.daReserva(id, u), booking.dentroDaJanelaCheckin(r));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Reserva solicitar(@Valid @RequestBody SolicitarReq req) {
        return booking.solicitar(AuthContext.exigir(Papel.LOCATARIO), req);
    }

    /** Retoma uma reserva que aguardava a aprovação do KYC. */
    @PostMapping("/{id}/continuar-kyc")
    public Reserva continuarKyc(@PathVariable String id) {
        return booking.continuarAposKyc(AuthContext.exigir(Papel.LOCATARIO), id);
    }

    @PostMapping("/{id}/aprovar")
    public Reserva aprovar(@PathVariable String id) {
        return booking.aprovar(AuthContext.exigir(Papel.PROPRIETARIO), id);
    }

    @PostMapping("/{id}/rejeitar")
    public Reserva rejeitar(@PathVariable String id, @RequestBody(required = false) MotivoReq req) {
        return booking.rejeitar(AuthContext.exigir(Papel.PROPRIETARIO), id, req == null ? null : req.motivo());
    }

    /** Cobra a diária e pré-autoriza a caução (mock). Cartão 0000… recusa o pagamento; 0001… recusa a caução. */
    @PostMapping("/{id}/pagar")
    public Reserva pagar(@PathVariable String id, @RequestBody(required = false) PagarReq req) {
        return booking.pagar(AuthContext.exigir(Papel.LOCATARIO), id, req);
    }

    @PostMapping("/{id}/cancelar")
    public Reserva cancelar(@PathVariable String id, @RequestBody(required = false) MotivoReq req) {
        return booking.cancelar(AuthContext.usuario(), id, req == null ? null : req.motivo());
    }

    @GetMapping("/{id}/endereco")
    public Map<String, Object> endereco(@PathVariable String id) {
        String e = booking.enderecoExato(AuthContext.usuario(), id);
        return java.util.Collections.singletonMap("enderecoExato", e);
    }

    // ───────────── vistorias (RF03) ─────────────

    @GetMapping("/{id}/vistorias/{tipo}")
    public Vistoria vistoria(@PathVariable String id, @PathVariable TipoVistoria tipo) {
        return booking.vistoria(AuthContext.usuario(), id, tipo);
    }

    @PutMapping("/{id}/vistorias/{tipo}")
    public Vistoria dadosVistoria(@PathVariable String id, @PathVariable TipoVistoria tipo, @Valid @RequestBody VistoriaDadosReq req) {
        return booking.atualizarDados(AuthContext.usuario(), id, tipo, req);
    }

    @PostMapping("/{id}/vistorias/{tipo}/fotos/{chave}")
    public Vistoria foto(@PathVariable String id, @PathVariable TipoVistoria tipo, @PathVariable FotoChave chave,
                         @RequestBody(required = false) FotoReq req) {
        return booking.enviarFoto(AuthContext.usuario(), id, tipo, chave, req == null ? null : req.localAproximada());
    }

    @PostMapping("/{id}/vistorias/{tipo}/aceite")
    public Vistoria aceite(@PathVariable String id, @PathVariable TipoVistoria tipo) {
        return booking.aceitar(AuthContext.usuario(), id, tipo);
    }

    /** Conclui o check-in/check-out; no check-out, {@code avaria=true} leva a reserva a EM_DISPUTA. */
    @PostMapping("/{id}/vistorias/{tipo}/concluir")
    public Reserva concluir(@PathVariable String id, @PathVariable TipoVistoria tipo, @RequestBody(required = false) ConcluirVistoriaReq req) {
        return booking.concluirVistoria(AuthContext.usuario(), id, tipo, req != null && req.avaria());
    }

    // ───────────── avaliação (RF06) ─────────────

    @PostMapping("/{id}/avaliacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public Avaliacao avaliar(@PathVariable String id, @Valid @RequestBody AvaliacaoReq req) {
        return reviews.enviar(AuthContext.usuario(), id, req);
    }

    @GetMapping("/{id}/avaliacoes")
    public List<Avaliacao> avaliacoes(@PathVariable String id) {
        Usuario u = AuthContext.usuario();
        booking.visivelPara(u, id);
        return reviews.daReserva(id, u);
    }

    // ───────────── disputa (visão das partes) ─────────────

    @PostMapping("/{id}/avaria")
    @ResponseStatus(HttpStatus.CREATED)
    public Disputa informarAvaria(@PathVariable String id, @Valid @RequestBody AvariaReq req) {
        return disputes.informarAvaria(AuthContext.exigir(Papel.PROPRIETARIO), id, req);
    }
}
