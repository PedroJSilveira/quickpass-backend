package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.Arquivo;
import br.com.quickpass.domain.Papel;
import br.com.quickpass.domain.Usuario;
import br.com.quickpass.common.ApiException;
import br.com.quickpass.service.BookingService;
import br.com.quickpass.service.StorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Download dos arquivos enviados. Fotos do anúncio são públicas; o resto (KYC, documentos do
 * veículo, vistorias, avaria, contestação) só o dono do arquivo, as partes da reserva e o admin.
 */
@RestController
@RequestMapping("/api/arquivos")
public class ArquivoController {
    private final StorageService storage;
    private final BookingService booking;

    public ArquivoController(StorageService storage, BookingService booking) {
        this.storage = storage;
        this.booking = booking;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> baixar(@PathVariable String id) {
        Arquivo a = storage.obrigatorio(id);
        CacheControl cache;
        if (a.categoria.publica()) {
            cache = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();
        } else {
            Usuario u = AuthContext.usuario();
            boolean permitido = u.id.equals(a.donoId) || u.temPapel(Papel.ADMIN);
            if (!permitido && a.reservaId != null) {
                booking.visivelPara(u, a.reservaId); // 403 se não participa da reserva
                permitido = true;
            }
            if (!permitido) throw ApiException.proibido("Você não tem acesso a este arquivo.");
            cache = CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(a.nome, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(cache)
                .body(storage.conteudo(a));
    }
}
