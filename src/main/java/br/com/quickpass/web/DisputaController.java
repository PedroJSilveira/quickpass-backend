package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.Disputa;
import br.com.quickpass.service.DisputeService;
import br.com.quickpass.web.Dtos.ContestacaoReq;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Disputas vistas pelas partes: listar e contestar. (Análise e decisão ficam em /api/admin.) */
@RestController
@RequestMapping("/api/disputas")
public class DisputaController {
    private final DisputeService disputes;

    public DisputaController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @GetMapping
    public List<Disputa> minhas() {
        return disputes.doUsuario(AuthContext.usuario());
    }

    /** Contestação em multipart: parte JSON {@code dados} (ContestacaoReq) e, opcional, as imagens na parte {@code evidencias}. */
    @PostMapping(value = "/{id}/contestacao", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Disputa contestar(@PathVariable String id, @Valid @RequestPart("dados") ContestacaoReq req,
                             @RequestPart(value = "evidencias", required = false) List<MultipartFile> evidencias) {
        return disputes.contestar(AuthContext.usuario(), id, req, evidencias);
    }
}
