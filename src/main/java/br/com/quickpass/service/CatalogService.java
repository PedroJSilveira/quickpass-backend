package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.*;
import br.com.quickpass.domain.*;
import br.com.quickpass.web.Dtos.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import static br.com.quickpass.common.Datas.diasEntre;
import static br.com.quickpass.common.Util.props;

/** Catálogo de veículos: cadastro, moderação, busca com filtros e ranking (RF01/RF06/RF17). */
@Service
public class CatalogService {
    private static final Pattern PLACA = Pattern.compile("^[A-Za-z]{3}-?[0-9][A-Za-z0-9][0-9]{2}$");

    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;
    private final EventBus bus;
    private final AgendaService agenda;
    private final ReviewService reviews;
    private final DemandService demanda;
    private final StorageService storage;
    private final UsuarioService usuarios;

    public CatalogService(Store store, AppConfigService config, ClockService clock, EventBus bus, AgendaService agenda,
                          ReviewService reviews, DemandService demanda, StorageService storage, UsuarioService usuarios) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.bus = bus;
        this.agenda = agenda;
        this.reviews = reviews;
        this.demanda = demanda;
        this.storage = storage;
        this.usuarios = usuarios;
    }

    // ───────────── consultas ─────────────

    public Veiculo obrigatorio(String id) {
        Veiculo v = store.veiculos.get(id);
        if (v == null) throw ApiException.naoEncontrado("Veículo não encontrado: " + id);
        return v;
    }

    public List<Veiculo> doProprietario(String proprietarioId) {
        return store.veiculos.values().stream().filter(v -> v.proprietarioId.equals(proprietarioId)).toList();
    }

    public List<Veiculo> porStatus(StatusVeiculo status) {
        return store.veiculos.values().stream().filter(v -> v.status == status).toList();
    }

    /** Agenda livre em todo o intervalo: sem conflito de reserva/hold e dias liberados pelo proprietário. */
    public boolean estaLivre(String veiculoId, LocalDate inicio, LocalDate fim) {
        return !agenda.temConflito(veiculoId, inicio, fim, null) && agenda.diasLiberados(veiculoId, diasEntre(inicio, fim));
    }

    public record Filtros(String local, LocalDate inicio, LocalDate fim, String categoria, Double precoMax, boolean registrar) {
    }

    /**
     * RF01: só veículos aprovados com agenda livre em TODO o intervalo, sem localização exata
     * nem dados pessoais, ordenados por {@link #pontuacao}. Busca submetida alimenta a demanda.
     */
    public List<VeiculoPublico> buscar(Filtros f) {
        if (f.inicio() != null && f.fim() != null && !f.fim().isAfter(f.inicio())) throw ApiException.regra("A devolução deve ser depois da retirada.");
        String categoria = f.categoria() == null || f.categoria().isBlank() ? "Todos" : f.categoria();
        CategoriaVeiculo cat = "Todos".equalsIgnoreCase(categoria) ? null : CategoriaVeiculo.deRotulo(categoria);
        var resultado = store.veiculos.values().stream()
                .filter(v -> v.status == StatusVeiculo.APROVADO)
                .filter(v -> f.local() == null || f.local().isBlank() || v.local.equalsIgnoreCase(f.local()) || f.local().contains(","))
                .filter(v -> cat == null || v.categoria == cat)
                .filter(v -> f.precoMax() == null || v.diaria <= f.precoMax())
                .filter(v -> f.inicio() == null || f.fim() == null || estaLivre(v.id, f.inicio(), f.fim()))
                .sorted(Comparator.comparingDouble(this::pontuacao).reversed())
                .map(this::publico).toList();
        if (f.registrar() && f.local() != null) {
            demanda.registrarBusca(f.local(), categoria, f.inicio(), f.fim());
            bus.emitir("busca_realizada", props("regiao_aproximada", f.local(), "dias", f.inicio() == null || f.fim() == null ? 0 : diasEntre(f.inicio(), f.fim()).size(),
                    "categoria", categoria, "resultados", resultado.size()));
        }
        return resultado;
    }

    /**
     * Ranking documentado (RF01/RF06/RF17, pesos em config):
     * pontuacao = wR·(nota/5) + wC·completude + (1 − wR − wC)·proximidade.
     */
    public double pontuacao(Veiculo v) {
        double wR = config.get(Parametro.PESO_REPUTACAO_RANKING);
        double wC = config.get(Parametro.PESO_COMPLETUDE_RANKING);
        double completude = ((v.foto != null && !v.foto.isBlank() ? 1 : 0) + Math.min(v.manutencao.size(), 4) / 4.0) / 2;
        double proximidade = 1 / (1 + v.distanciaKm);
        return wR * (reviews.notaParaRanking(v.id) / 5) + wC * completude + (1 - wR - wC) * proximidade;
    }

    public VeiculoPublico publico(Veiculo v) {
        Usuario dono = store.usuarios.get(v.proprietarioId);
        String nome = dono == null ? "Proprietário" : dono.nome;
        String[] partes = nome.trim().split("\\s+");
        String curto = partes.length > 1 ? partes[0] + " " + partes[partes.length - 1].charAt(0) + "." : partes[0];
        String iniciais = partes.length > 1 ? "" + partes[0].charAt(0) + partes[partes.length - 1].charAt(0) : "" + partes[0].charAt(0);
        var rep = reviews.reputacaoDoProprietario(v.proprietarioId).media();
        double notaDono = rep != null ? rep : (dono == null ? 0 : dono.nota);
        return new VeiculoPublico(v.id, v.marca + " " + v.modelo + " " + v.ano, v.foto, v.fotos.isEmpty() && v.foto != null ? List.of(v.foto) : List.copyOf(v.fotos), v.marca, v.modelo, v.ano, v.categoria, v.diaria,
                v.local, v.distanciaKm, v.cambio, v.combustivel, v.lugares, v.km, v.consumo, v.proprietarioId, curto,
                iniciais.toUpperCase(), notaDono, v.status, v.nota, v.locacoes, v.manutencao);
    }

    public VeiculoPublico publicoPorId(String id) {
        return publico(obrigatorio(id));
    }

    // ───────────── cadastro / moderação ─────────────

    /** Anúncio novo, com fotos (mínimo configurável) e documentos opcionais (CRLV, seguro). Exige KYC aprovado (RF02). */
    public Veiculo cadastrar(Usuario dono, VeiculoReq r, List<MultipartFile> fotos, List<MultipartFile> documentos) {
        if (!usuarios.verificarExpiracao(dono)) throw ApiException.proibido("Verificação de identidade (KYC) pendente: não é possível anunciar um veículo.");
        int minimo = config.getInt(Parametro.FOTOS_VEICULO_MINIMAS);
        if (StorageService.contar(fotos) < minimo) throw ApiException.regra("Envie ao menos %d fotos do veículo.".formatted(minimo));
        if (!PLACA.matcher(r.placa()).matches()) throw ApiException.regra("Placa inválida (use o padrão antigo ou Mercosul).");
        String placa = r.placa().toUpperCase().replace("-", "");
        boolean duplicada = store.veiculos.values().stream()
                .anyMatch(v -> v.status != StatusVeiculo.RECUSADO && v.placa.toUpperCase().replace("-", "").equals(placa));
        if (duplicada) throw ApiException.conflito("Já existe um veículo ativo ou em análise com esta placa.");
        Veiculo v = new Veiculo();
        v.id = "V-" + store.proximo("veiculo", 100);
        v.titulo = r.marca() + " " + r.modelo() + " " + r.ano();
        v.marca = r.marca();
        v.modelo = r.modelo();
        v.ano = r.ano();
        v.placa = r.placa().toUpperCase();
        v.categoria = r.categoria();
        v.diaria = r.diaria();
        v.local = r.local();
        v.enderecoExato = r.enderecoExato();
        v.distanciaKm = r.distanciaKm() == null ? 0 : r.distanciaKm();
        v.cambio = r.cambio();
        v.combustivel = r.combustivel();
        v.lugares = r.lugares();
        v.km = r.km();
        v.consumo = r.consumo();
        v.proprietarioId = dono.id;
        v.status = StatusVeiculo.EM_ANALISE;
        v.docValidoAte = r.docValidoAte();
        if (r.manutencao() != null) v.manutencao = new ArrayList<>(r.manutencao());
        // Arquivos só são gravados depois de todas as validações de negócio passarem.
        List<Arquivo> salvas = storage.salvarTodos(fotos, CategoriaArquivo.VEICULO_FOTO, dono.id, null, false);
        List<Arquivo> docs;
        try {
            docs = storage.salvarTodos(documentos, CategoriaArquivo.VEICULO_DOCUMENTO, dono.id, null, true);
        } catch (RuntimeException e) {
            salvas.forEach(a -> storage.remover(a.id));
            throw e;
        }
        salvas.forEach(a -> v.fotos.add(a.getUrl()));
        v.foto = v.fotos.get(0);
        v.documentos = new ArrayList<>(docs);
        store.veiculos.put(v.id, v);
        bus.emitir("veiculo_listado", props("user_id", dono.id, "vehicle_id", v.id, "categoria", v.categoria.rotulo(), "regiao_aproximada", v.local));
        return v;
    }

    public Veiculo atualizar(Usuario dono, String id, VeiculoAtualizacaoReq r) {
        Veiculo v = agenda.veiculoDoUsuario(dono, id);
        if (r.diaria() != null) v.diaria = r.diaria();
        if (r.enderecoExato() != null) v.enderecoExato = r.enderecoExato();
        if (r.km() != null) v.km = r.km();
        if (r.docValidoAte() != null) v.docValidoAte = r.docValidoAte();
        if (r.manutencao() != null) v.manutencao = new ArrayList<>(r.manutencao());
        return v;
    }

    /** Acrescenta fotos ao anúncio (o limite mínimo só vale no cadastro). */
    public Veiculo adicionarFotos(Usuario dono, String id, List<MultipartFile> fotos) {
        Veiculo v = agenda.veiculoDoUsuario(dono, id);
        if (StorageService.contar(fotos) == 0) throw ApiException.regra("Envie ao menos uma foto.");
        for (Arquivo a : storage.salvarTodos(fotos, CategoriaArquivo.VEICULO_FOTO, dono.id, null, false)) v.fotos.add(a.getUrl());
        v.foto = v.fotos.get(0);
        return v;
    }

    /** Remove uma foto do anúncio; o anúncio precisa manter ao menos uma. */
    public Veiculo removerFoto(Usuario dono, String id, String arquivoId) {
        Veiculo v = agenda.veiculoDoUsuario(dono, id);
        String url = "/api/arquivos/" + arquivoId;
        if (!v.fotos.contains(url)) throw ApiException.naoEncontrado("Foto não encontrada neste anúncio.");
        if (v.fotos.size() <= 1) throw ApiException.regra("O anúncio precisa de ao menos uma foto.");
        v.fotos.remove(url);
        storage.remover(arquivoId);
        v.foto = v.fotos.get(0);
        return v;
    }

    /** Moderação (admin): aprova ou recusa um veículo em análise. */
    public Veiculo moderar(String id, boolean aprovar, String motivo) {
        Veiculo v = obrigatorio(id);
        if (v.status != StatusVeiculo.EM_ANALISE) throw ApiException.regra("Só é possível moderar um veículo em análise.");
        if (!aprovar && (motivo == null || motivo.isBlank())) throw ApiException.regra("Informe o motivo da recusa.");
        v.status = aprovar ? StatusVeiculo.APROVADO : StatusVeiculo.RECUSADO;
        v.motivoRecusa = aprovar ? null : motivo;
        return v;
    }
}
