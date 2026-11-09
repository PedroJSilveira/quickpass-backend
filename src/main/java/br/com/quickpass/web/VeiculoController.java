package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.Avaliacao;
import br.com.quickpass.domain.Papel;
import br.com.quickpass.domain.StatusVeiculo;
import br.com.quickpass.domain.Usuario;
import br.com.quickpass.domain.Veiculo;
import br.com.quickpass.service.AgendaService;
import br.com.quickpass.service.CatalogService;
import br.com.quickpass.service.ReviewService;
import br.com.quickpass.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

    public VeiculoController(CatalogService catalog, ReviewService reviews, AgendaService agenda) {
        this.catalog = catalog;
        this.reviews = reviews;
        this.agenda = agenda;
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
        return catalog.publico(v);
    }

    /** Avaliações já publicadas (RF06) sobre o veículo. */
    @GetMapping("/veiculos/{id}/avaliacoes")
    public Map<String, Object> avaliacoes(@PathVariable String id) {
        catalog.obrigatorio(id);
        List<Avaliacao> lista = reviews.publicadasDoVeiculo(id);
        return Map.of("reputacao", reviews.reputacaoDoVeiculo(id), "avaliacoes", lista);
    }

    // ───────────── proprietário ─────────────

    @PostMapping("/proprietario/veiculos")
    @ResponseStatus(HttpStatus.CREATED)
    public Veiculo cadastrar(@Valid @RequestBody VeiculoReq req) {
        return catalog.cadastrar(AuthContext.exigir(Papel.PROPRIETARIO), req);
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
