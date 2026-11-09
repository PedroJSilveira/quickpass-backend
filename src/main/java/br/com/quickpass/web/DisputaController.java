package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.Disputa;
import br.com.quickpass.service.DisputeService;
import br.com.quickpass.web.Dtos.ContestacaoReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/{id}/contestacao")
    public Disputa contestar(@PathVariable String id, @Valid @RequestBody ContestacaoReq req) {
        return disputes.contestar(AuthContext.usuario(), id, req);
    }
}
