package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.Arquivo;
import br.com.quickpass.domain.CategoriaArquivo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Armazenamento dos arquivos enviados por multipart (fotos, documentos, evidências). O tipo é
 * decidido pelo CONTEÚDO (assinatura dos primeiros bytes), nunca pelo Content-Type nem pelo nome
 * informados pelo cliente; o nome em disco é gerado pelo servidor.
 */
@Service
public class StorageService {
    /** Tipos aceitos: imagens e (quando permitido) PDF. */
    private enum Tipo {
        JPEG("image/jpeg", "jpg", false), PNG("image/png", "png", false), WEBP("image/webp", "webp", false), GIF("image/gif", "gif", false),
        AVIF("image/avif", "avif", false), HEIC("image/heic", "heic", false), PDF("application/pdf", "pdf", true);

        final String mime;
        final String extensao;
        final boolean documento;

        Tipo(String mime, String extensao, boolean documento) {
            this.mime = mime;
            this.extensao = extensao;
            this.documento = documento;
        }
    }

    private final Store store;
    private final ClockService clock;
    private final Path raiz;
    private final long maxBytes;

    public StorageService(Store store, ClockService clock, @Value("${quickpass.uploads.dir:./uploads}") String dir,
                          @Value("${quickpass.uploads.max-bytes:10485760}") long maxBytes) throws IOException {
        this.store = store;
        this.clock = clock;
        this.raiz = Path.of(dir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        Files.createDirectories(raiz);
    }

    /** Salva uma imagem (ou PDF, se {@code aceitaPdf}). */
    public Arquivo salvar(MultipartFile mf, CategoriaArquivo categoria, String donoId, String reservaId, boolean aceitaPdf) {
        Tipo tipo = detectar(mf, aceitaPdf);
        Arquivo a = new Arquivo();
        a.id = "F-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        a.nome = nomeSeguro(mf.getOriginalFilename(), tipo);
        a.contentType = tipo.mime;
        a.tamanho = mf.getSize();
        a.enviadoEm = clock.now();
        a.categoria = categoria;
        a.donoId = donoId;
        a.reservaId = reservaId;
        a.armazenado = a.id + "." + tipo.extensao;
        try (InputStream in = mf.getInputStream()) {
            Files.copy(in, raiz.resolve(a.armazenado));
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao gravar o arquivo enviado.", e);
        }
        store.arquivos.put(a.id, a);
        return a;
    }

    /**
     * Salva vários de uma vez, tudo ou nada: valida todos antes de gravar o primeiro e, se algo
     * falhar no meio, remove o que já foi gravado.
     */
    public List<Arquivo> salvarTodos(List<MultipartFile> arquivos, CategoriaArquivo categoria, String donoId, String reservaId, boolean aceitaPdf) {
        List<MultipartFile> validos = nonVazios(arquivos);
        validos.forEach(mf -> detectar(mf, aceitaPdf));
        List<Arquivo> salvos = new ArrayList<>();
        try {
            for (MultipartFile mf : validos) salvos.add(salvar(mf, categoria, donoId, reservaId, aceitaPdf));
        } catch (RuntimeException e) {
            salvos.forEach(a -> remover(a.id));
            throw e;
        }
        return salvos;
    }

    /** Quantidade de arquivos de fato enviados (ignora campos de arquivo vazios). */
    public static int contar(List<MultipartFile> arquivos) {
        return nonVazios(arquivos).size();
    }

    private static List<MultipartFile> nonVazios(List<MultipartFile> arquivos) {
        return arquivos == null ? List.of() : arquivos.stream().filter(f -> f != null && !f.isEmpty()).toList();
    }

    public Arquivo obrigatorio(String id) {
        Arquivo a = store.arquivos.get(id);
        if (a == null) throw ApiException.naoEncontrado("Arquivo não encontrado.");
        return a;
    }

    public Resource conteudo(Arquivo a) {
        Path p = raiz.resolve(a.armazenado).normalize();
        if (!p.startsWith(raiz) || !Files.isReadable(p)) throw ApiException.naoEncontrado("Arquivo não encontrado.");
        return new FileSystemResource(p);
    }

    public void remover(String id) {
        Arquivo a = store.arquivos.remove(id);
        if (a == null) return;
        try {
            Files.deleteIfExists(raiz.resolve(a.armazenado));
        } catch (IOException ignorado) {
            // arquivo órfão em disco não afeta o funcionamento
        }
    }

    // ───────────── validação ─────────────

    private Tipo detectar(MultipartFile mf, boolean aceitaPdf) {
        if (mf == null || mf.isEmpty()) throw ApiException.regra("Arquivo vazio ou não enviado.");
        if (mf.getSize() > maxBytes) throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo maior que o limite de %d MB.".formatted(maxBytes / 1_048_576));
        byte[] h = new byte[16];
        int n;
        try (InputStream in = mf.getInputStream()) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            throw ApiException.regra("Não foi possível ler o arquivo enviado.");
        }
        Tipo t = porAssinatura(h, n);
        if (t == null || (t.documento && !aceitaPdf)) {
            throw ApiException.regra(aceitaPdf ? "Formato inválido: envie uma imagem (JPG, PNG, WEBP, HEIC, AVIF) ou PDF." : "Formato inválido: envie uma imagem (JPG, PNG, WEBP, HEIC, AVIF).");
        }
        return t;
    }

    private static Tipo porAssinatura(byte[] h, int n) {
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return Tipo.JPEG;
        if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return Tipo.PNG;
        if (n >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return Tipo.GIF;
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return Tipo.PDF;
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return Tipo.WEBP;
        if (n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            String marca = new String(h, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (marca.startsWith("avi")) return Tipo.AVIF;
            if (marca.startsWith("hei") || marca.startsWith("mif") || marca.startsWith("msf")) return Tipo.HEIC;
        }
        return null;
    }

    private static String nomeSeguro(String original, Tipo tipo) {
        String base = original == null ? "" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>\"|?*:]", "").trim();
        if (base.isBlank()) base = "arquivo." + tipo.extensao;
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }
}
