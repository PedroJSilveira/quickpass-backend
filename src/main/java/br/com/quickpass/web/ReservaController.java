package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.*;
import br.com.quickpass.service.*;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import br.com.quickpass.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Reservas: solicitar, aprovar, pagar, cancelar, vistorias (check-in/out) e avaliações. */
@RestController
@RequestMapping("/api/reservas")
public class ReservaController {
    public record ReservaDetalhe(ReservaView reserva, VeiculoPublico veiculo, Pagamento pagamento, Caucao caucao, String enderecoExato,
                                 Disputa disputa, List<Avaliacao> avaliacoes, boolean dentroDaJanelaCheckin) {
    }

    /**
     * Reserva + dados de apoio às listas (veículo, nome abreviado do locatário, situação da disputa), para a
     * tela não precisar de uma chamada por item. Os campos da reserva continuam no nível raiz do JSON.
     */
    public record ReservaView(@JsonUnwrapped Reserva reserva, String veiculoTitulo, String veiculoFoto, String proprietarioId,
                              String locatarioNome, String disputaId, StatusDisputa disputaStatus) {
    }

    private final BookingService booking;
    private final CatalogService catalog;
    private final DepositService deposit;
    private final DisputeService disputes;
    private final ReviewService reviews;
    private final UsuarioService usuarios;

    public ReservaController(BookingService booking, CatalogService catalog, DepositService deposit, DisputeService disputes,
                             ReviewService reviews, UsuarioService usuarios) {
        this.booking = booking;
        this.catalog = catalog;
        this.deposit = deposit;
        this.disputes = disputes;
        this.reviews = reviews;
        this.usuarios = usuarios;
    }

    private ReservaView view(Reserva r) {
        Veiculo v = booking.veiculoDa(r);
        Disputa d = disputes.daReserva(r.id);
        String nome = usuarios.obrigatorio(r.locatarioId).nome.trim();
        String[] p = nome.split("\\s+");
        String curto = p.length > 1 ? p[0] + " " + p[p.length - 1].charAt(0) + "." : p[0];
        return new ReservaView(r, v == null ? null : v.titulo, v == null ? null : v.foto, v == null ? null : v.proprietarioId,
                curto, d == null ? null : d.id, d == null ? null : d.status);
    }

    /** Lista as reservas do usuário como {@code locatario} (padrão) ou {@code proprietario}. */
    @GetMapping
    public List<ReservaView> listar(@RequestParam(defaultValue = "locatario") String comoParam) {
        Papel como = Papel.valueOf(comoParam.trim().toUpperCase());
        if (como == Papel.ADMIN) throw br.com.quickpass.common.ApiException.regra("Use como=locatario ou como=proprietario.");
        Usuario u = AuthContext.exigir(como);
        return (como == Papel.PROPRIETARIO ? booking.doProprietario(u) : como == Papel.LOCATARIO ? booking.doLocatario(u) : List.<Reserva>of())
                .stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public ReservaDetalhe detalhe(@PathVariable String id) {
        Usuario u = AuthContext.usuario();
        Reserva r = booking.visivelPara(u, id);
        return new ReservaDetalhe(view(r), catalog.publico(booking.veiculoDa(r)), booking.pagamentoDaReserva(id), deposit.porReserva(id),
                booking.enderecoExato(u, id), disputes.daReserva(id), reviews.daReserva(id, u), booking.dentroDaJanelaCheckin(r));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservaView solicitar(@Valid @RequestBody SolicitarReq req) {
        return view(booking.solicitar(AuthContext.exigir(Papel.LOCATARIO), req));
    }

    /** Retoma uma reserva que aguardava a aprovação do KYC. */
    @PostMapping("/{id}/continuar-kyc")
    public ReservaView continuarKyc(@PathVariable String id) {
        return view(booking.continuarAposKyc(AuthContext.exigir(Papel.LOCATARIO), id));
    }

    @PostMapping("/{id}/aprovar")
    public ReservaView aprovar(@PathVariable String id) {
        return view(booking.aprovar(AuthContext.exigir(Papel.PROPRIETARIO), id));
    }

    @PostMapping("/{id}/rejeitar")
    public ReservaView rejeitar(@PathVariable String id, @RequestBody(required = false) MotivoReq req) {
        return view(booking.rejeitar(AuthContext.exigir(Papel.PROPRIETARIO), id, req == null ? null : req.motivo()));
    }

    /** Cobra a diária e pré-autoriza a caução (mock). Cartão 0000… recusa o pagamento; 0001… recusa a caução. */
    @PostMapping("/{id}/pagar")
    public ReservaView pagar(@PathVariable String id, @RequestBody(required = false) PagarReq req) {
        return view(booking.pagar(AuthContext.exigir(Papel.LOCATARIO), id, req));
    }

    @PostMapping("/{id}/cancelar")
    public ReservaView cancelar(@PathVariable String id, @RequestBody(required = false) MotivoReq req) {
        return view(booking.cancelar(AuthContext.usuario(), id, req == null ? null : req.motivo()));
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

    /** Foto da vistoria em multipart: parte {@code arquivo} (imagem) e, opcional, o campo {@code localAproximada} (bairro). */
    @PostMapping(value = "/{id}/vistorias/{tipo}/fotos/{chave}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Vistoria foto(@PathVariable String id, @PathVariable TipoVistoria tipo, @PathVariable FotoChave chave,
                         @RequestPart("arquivo") MultipartFile arquivo, @RequestParam(required = false) String localAproximada) {
        return booking.enviarFoto(AuthContext.usuario(), id, tipo, chave, arquivo, localAproximada);
    }

    @PostMapping("/{id}/vistorias/{tipo}/aceite")
    public Vistoria aceite(@PathVariable String id, @PathVariable TipoVistoria tipo) {
        return booking.aceitar(AuthContext.usuario(), id, tipo);
    }

    /** Conclui o check-in/check-out; no check-out, {@code avaria=true} leva a reserva a EM_DISPUTA. */
    @PostMapping("/{id}/vistorias/{tipo}/concluir")
    public ReservaView concluir(@PathVariable String id, @PathVariable TipoVistoria tipo, @RequestBody(required = false) ConcluirVistoriaReq req) {
        return view(booking.concluirVistoria(AuthContext.usuario(), id, tipo, req != null && req.avaria()));
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

    /** Avaria em multipart: parte JSON {@code dados} (AvariaReq) e as imagens na parte {@code fotos} (ao menos uma). */
    @PostMapping(value = "/{id}/avaria", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Disputa informarAvaria(@PathVariable String id, @Valid @RequestPart("dados") AvariaReq req,
                                  @RequestPart(value = "fotos", required = false) List<MultipartFile> fotos) {
        return disputes.informarAvaria(AuthContext.exigir(Papel.PROPRIETARIO), id, req, fotos);
    }
}
