package br.com.quickpass.web;

import br.com.quickpass.config.AuthContext;
import br.com.quickpass.domain.*;
import br.com.quickpass.service.AgendaReminderService;
import br.com.quickpass.service.AgendaService;
import br.com.quickpass.service.DemandService;
import br.com.quickpass.service.RevenueSimulationService;
import br.com.quickpass.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Agenda do proprietário: calendário, "liberar com um toque" (RF11), preferências (RF17) e simulação de receita (RF05). */
@RestController
@RequestMapping("/api/proprietario")
public class AgendaController {
    private final AgendaService agenda;
    private final RevenueSimulationService simulacao;
    private final DemandService demanda;
    private final AgendaReminderService lembretes;

    public AgendaController(AgendaService agenda, RevenueSimulationService simulacao, DemandService demanda, AgendaReminderService lembretes) {
        this.lembretes = lembretes;
        this.agenda = agenda;
        this.simulacao = simulacao;
        this.demanda = demanda;
    }

    private Usuario dono() {
        return AuthContext.exigir(Papel.PROPRIETARIO);
    }

    @GetMapping("/veiculos/{id}/disponibilidade")
    public Disponibilidade disponibilidade(@PathVariable String id) {
        agenda.veiculoDoUsuario(dono(), id);
        return agenda.disponibilidadeDoVeiculo(id);
    }

    @PutMapping("/veiculos/{id}/disponibilidade")
    public Disponibilidade salvar(@PathVariable String id, @Valid @RequestBody DisponibilidadeReq req,
                                  @RequestParam(defaultValue = "calendario") String origem) {
        return agenda.salvar(dono(), id, req, origem);
    }

    @PostMapping("/veiculos/{id}/disponibilidade/dias/{dia}/alternar")
    public Disponibilidade alternarDia(@PathVariable String id, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dia) {
        return agenda.alternarDia(dono(), id, dia);
    }

    // ───────────── RF11 ─────────────

    @GetMapping("/template-disponibilidade")
    public TemplateDisponibilidade template() {
        return agenda.template(dono());
    }

    @PutMapping("/template-disponibilidade")
    public TemplateDisponibilidade salvarTemplate(@Valid @RequestBody TemplateReq req) {
        return agenda.salvarTemplate(dono(), new TemplateDisponibilidade(req.tipo(), req.semanas()));
    }

    @GetMapping("/veiculos/{id}/um-toque/previa")
    public AgendaService.Previa previa(@PathVariable String id) {
        return agenda.previaUmToque(dono(), id);
    }

    @PostMapping("/veiculos/{id}/um-toque")
    public AgendaService.ResultadoUmToque umToque(@PathVariable String id, @RequestParam(defaultValue = "um_toque") String origem) {
        return agenda.liberarComUmToque(dono(), id, origem);
    }

    @PostMapping("/veiculos/{id}/um-toque/desfazer")
    public Disponibilidade desfazer(@PathVariable String id) {
        return agenda.desfazer(dono(), id);
    }

    // ───────────── RF17 ─────────────

    @GetMapping("/veiculos/{id}/preferencias")
    public PreferenciasAnuncio preferencias(@PathVariable String id) {
        agenda.veiculoDoUsuario(dono(), id);
        return agenda.preferencias(id);
    }

    @PutMapping("/veiculos/{id}/preferencias")
    public PreferenciasAnuncio salvarPreferencias(@PathVariable String id, @Valid @RequestBody PreferenciasReq req) {
        return agenda.salvarPreferencias(dono(), id, new PreferenciasAnuncio(req.antecedenciaMinimaHoras(), req.duracaoMinimaDias()));
    }

    @GetMapping("/veiculos/{id}/sugestoes-datas")
    public List<LocalDate> sugestoes(@PathVariable String id, @RequestParam(defaultValue = "4") int quantidade) {
        return agenda.sugerirDatas(dono(), id, quantidade);
    }

    @GetMapping("/veiculos/{id}/recompensa")
    public AgendaService.Recompensa recompensa(@PathVariable String id) {
        agenda.veiculoDoUsuario(dono(), id);
        return agenda.recompensa(id);
    }

    // ───────────── RF05 / RF16 ─────────────

    @GetMapping("/veiculos/{id}/simulacao-receita")
    public RevenueSimulationService.SimulacaoReceita simulacao(@PathVariable String id) {
        return simulacao.simular(dono(), id);
    }

    /** Sinal de demanda da região (bairro): sempre dado, com "amostra insuficiente" quando aplicável. */
    @GetMapping("/demanda")
    public DemandService.DemandaRegional demanda(@RequestParam String regiao) {
        dono();
        return demanda.demanda(regiao);
    }

    /** O proprietário recusa a régua de lembretes de agenda: ela encerra de vez para ele (RF07). */
    @PostMapping("/lembretes/recusar")
    public java.util.Map<String, String> recusarLembretes() {
        lembretes.recusar(dono().id);
        return java.util.Map.of("status", "ok");
    }
}
