package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.Avaliacao;
import br.com.quickpass.domain.Protecao;
import br.com.quickpass.domain.Valores;
import br.com.quickpass.domain.Papel;
import br.com.quickpass.domain.StatusVeiculo;
import br.com.quickpass.domain.Usuario;
import br.com.quickpass.domain.Veiculo;
import br.com.quickpass.service.AgendaService;
import br.com.quickpass.service.CatalogService;
import br.com.quickpass.service.EventBus;
import br.com.quickpass.service.PricingService;
import br.com.quickpass.service.ReviewService;
import br.com.quickpass.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Busca pública de veículos (locatário) e gestão dos próprios veículos (proprietário). */
@RestController
@RequestMapping("/api")
public class VeiculoController {
    private final CatalogService catalog;
    private final ReviewService reviews;
    private final AgendaService agenda;
    private final PricingService pricing;
    private final EventBus bus;

    public VeiculoController(CatalogService catalog, ReviewService reviews, AgendaService agenda, PricingService pricing, EventBus bus) {
        this.catalog = catalog;
        this.reviews = reviews;
        this.agenda = agenda;
        this.pricing = pricing;
        this.bus = bus;
    }

    // ───────────── público ─────────────

    /** Busca (RF01): só aprovados com agenda livre no período; sem endereço exato nem placa. {@code registrar=false} não conta como busca de demanda. */
    @GetMapping("/veiculos")
    public List<VeiculoPublico> buscar(@RequestParam(required = false) String local,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
                                       @RequestParam(required = false) String categoria,
                                       @RequestParam(required = false) Double precoMax,
                                       @RequestParam(defaultValue = "true") boolean registrar) {
        return catalog.buscar(new CatalogService.Filtros(local, inicio, fim, categoria, precoMax, registrar));
    }

    @GetMapping("/veiculos/{id}")
    public VeiculoPublico detalhe(@PathVariable String id) {
        Veiculo v = catalog.obrigatorio(id);
        if (v.status != StatusVeiculo.APROVADO) {
            Usuario u = AuthContext.atual() == null ? null : AuthContext.atual().usuario();
            if (u == null || !(v.proprietarioId.equals(u.id) || u.temPapel(Papel.ADMIN))) {
                throw br.com.quickpass.common.ApiException.naoEncontrado("Veículo não encontrado: " + id);
            }
        }
        Usuario atual = AuthContext.atual() == null ? null : AuthContext.atual().usuario();
        if (v.status == StatusVeiculo.APROVADO && (atual == null || !v.proprietarioId.equals(atual.id))) {
            bus.emitir("disponibilidade_visualizada", br.com.quickpass.common.Util.props("user_id", atual == null ? null : atual.id, "vehicle_id", v.id));
        }
        return catalog.publico(v);
    }

    /** Valores da locação (diárias, proteção, taxa, total, caução) para a tela de reserva — fonte única: PricingService. */
    @GetMapping("/veiculos/{id}/cotacao")
    public Valores cotacao(@PathVariable String id,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
                           @RequestParam(defaultValue = "COMPLETA") Protecao protecao) {
        Veiculo v = catalog.obrigatorio(id);
        if (v.status != StatusVeiculo.APROVADO) throw br.com.quickpass.common.ApiException.naoEncontrado("Veículo não encontrado: " + id);
        if (!fim.isAfter(inicio)) throw br.com.quickpass.common.ApiException.regra("A devolução deve ser depois da retirada.");
        return pricing.calcular(v.diaria, protecao, inicio, fim);
    }

    /** Avaliações já publicadas (RF06) sobre o veículo. */
    @GetMapping("/veiculos/{id}/avaliacoes")
    public Map<String, Object> avaliacoes(@PathVariable String id) {
        catalog.obrigatorio(id);
        List<Avaliacao> lista = reviews.publicadasDoVeiculo(id);
        return Map.of("reputacao", reviews.reputacaoDoVeiculo(id), "avaliacoes", lista);
    }

    // ───────────── proprietário ─────────────

    /**
     * Cadastro do anúncio em multipart/form-data: parte JSON {@code dados} (VeiculoReq), {@code fotos}
     * (imagens, repetir a parte) e {@code documentos} (CRLV/seguro; imagem ou PDF, opcional).
     */
    @PostMapping(value = "/proprietario/veiculos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Veiculo cadastrar(@Valid @RequestPart("dados") VeiculoReq req,
                             @RequestPart(value = "fotos", required = false) List<MultipartFile> fotos,
                             @RequestPart(value = "documentos", required = false) List<MultipartFile> documentos) {
        return catalog.cadastrar(AuthContext.exigir(Papel.PROPRIETARIO), req, fotos, documentos);
    }

    @PostMapping(value = "/proprietario/veiculos/{id}/fotos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Veiculo adicionarFotos(@PathVariable String id, @RequestPart("fotos") List<MultipartFile> fotos) {
        return catalog.adicionarFotos(AuthContext.exigir(Papel.PROPRIETARIO), id, fotos);
    }

    @DeleteMapping("/proprietario/veiculos/{id}/fotos/{arquivoId}")
    public Veiculo removerFoto(@PathVariable String id, @PathVariable String arquivoId) {
        return catalog.removerFoto(AuthContext.exigir(Papel.PROPRIETARIO), id, arquivoId);
    }

    @GetMapping("/proprietario/veiculos")
    public List<Veiculo> meusVeiculos() {
        return catalog.doProprietario(AuthContext.exigir(Papel.PROPRIETARIO).id);
    }

    @GetMapping("/proprietario/veiculos/{id}")
    public Veiculo meuVeiculo(@PathVariable String id) {
        return agenda.veiculoDoUsuario(AuthContext.exigir(Papel.PROPRIETARIO), id);
    }

    @PutMapping("/proprietario/veiculos/{id}")
    public Veiculo atualizar(@PathVariable String id, @Valid @RequestBody VeiculoAtualizacaoReq req) {
        return catalog.atualizar(AuthContext.exigir(Papel.PROPRIETARIO), id, req);
    }
}
