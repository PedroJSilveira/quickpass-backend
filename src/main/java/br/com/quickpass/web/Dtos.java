package br.com.quickpass.web;

import br.com.quickpass.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contratos de entrada/saída da API. */
public final class Dtos {
    private Dtos() {
    }

    // ───────────── auth ─────────────
    public record AtribuicaoReq(String utmSource, String utmMedium, String utmCampaign, String utmTerm,
                                String utmContent, String ref, String referrer) {
    }

    public record CadastroReq(@NotBlank String nome, @NotBlank @Email String email, @NotBlank @Size(min = 6) String senha,
                              @NotBlank String cpf, @NotEmpty Set<Papel> papeis, @PositiveOrZero Integer cnhAnos,
                              @Valid AtribuicaoReq atribuicao, String dispositivo) {
    }

    public record LoginReq(@NotBlank String email, @NotBlank String senha, String origem, String notificacaoId) {
    }

    public record SessaoRes(String token, Usuario usuario) {
    }

    public record PapeisReq(@NotEmpty Set<Papel> papeis) {
    }

    public record ConsentimentoReq(@NotNull CanalNotificacao canal, @NotNull FinalidadeConsentimento finalidade, boolean valor) {
    }

    // ───────────── veículos / agenda ─────────────
    public record VeiculoReq(@NotBlank String marca, @NotBlank String modelo, @Min(1990) @Max(2100) int ano,
                             @NotBlank String placa, @NotNull CategoriaVeiculo categoria, @Positive double diaria,
                             @NotBlank String local, @NotBlank String enderecoExato, @NotNull Cambio cambio,
                             @NotNull Combustivel combustivel, @Min(1) int lugares, @PositiveOrZero int km,
                             String consumo, String foto, Double distanciaKm, LocalDate docValidoAte,
                             List<ItemManutencao> manutencao) {
    }

    public record VeiculoAtualizacaoReq(@Positive Double diaria, String foto, String enderecoExato, @PositiveOrZero Integer km,
                                        LocalDate docValidoAte, List<ItemManutencao> manutencao) {
    }

    public record MotivoReq(String motivo) {
    }

    public record DisponibilidadeReq(@NotNull Map<LocalDate, Boolean> dias, boolean aceiteAutomatico) {
    }

    public record TemplateReq(@NotNull TemplateDisponibilidade.Tipo tipo, @Min(1) @Max(12) int semanas) {
    }

    public record PreferenciasReq(@PositiveOrZero int antecedenciaMinimaHoras, @Min(1) int duracaoMinimaDias) {
    }

    // ───────────── reservas ─────────────
    public record SolicitarReq(@NotBlank String veiculoId, @NotNull LocalDate inicio, @NotNull LocalDate fim,
                               @NotNull Protecao protecao, String finalidade, String origem) {
    }

    /** metodo: "pix" | "cartao". Cartão iniciado em 0000 simula recusa; 0001 simula falha na caução. */
    public record PagarReq(String metodo, String numeroCartao) {
    }

    public record FotoReq(String localAproximada) {
    }

    public record VistoriaDadosReq(@PositiveOrZero Integer km, String combustivel, String observacoes) {
    }

    public record ConcluirVistoriaReq(boolean avaria) {
    }

    // ───────────── disputas / avaliações ─────────────
    public record AvariaReq(@NotBlank String descricao, @Min(0) int qtdFotos, @PositiveOrZero double valorEstimado) {
    }

    public record ContestacaoReq(@NotBlank String texto, @Min(0) int qtdEvidencias) {
    }

    public record DecisaoReq(@NotNull TipoDecisao tipo, @PositiveOrZero double valor, @NotBlank String justificativa) {
    }

    public record AvaliacaoReq(@Min(1) @Max(5) int nota, String texto, List<String> selos) {
    }

    // ───────────── hábito / growth ─────────────
    public record BuscaSalvaReq(@NotBlank String regiao, @Positive double raioKm, @NotNull LocalDate inicio, @NotNull LocalDate fim,
                                String categoria, Double precoMax, boolean alertasAtivos, boolean canalConsentido) {
    }

    public record ParametroReq(@NotNull Double valor) {
    }

    public record GatewayReq(boolean falharCobranca, boolean falharPreAutorizacao) {
    }

    public record AvancarRelogioReq(@Positive long horas) {
    }

    // ───────────── respostas ─────────────
    /** Veículo como exposto na busca/público: sem endereço exato nem placa (RNF02). */
    public record VeiculoPublico(String id, String titulo, String foto, String marca, String modelo, int ano,
                                 CategoriaVeiculo categoria, double diaria, String local, double distanciaKm,
                                 Cambio cambio, Combustivel combustivel, int lugares, int km, String consumo,
                                 String proprietarioId, String proprietarioNome, String proprietarioIniciais,
                                 double proprietarioNota, StatusVeiculo status, Double nota, int locacoes,
                                 List<ItemManutencao> manutencao) {
    }

    public record LocatarioResumo(String nome, int cnhAnos, double nota, int locacoes) {
    }

    /** Solicitação vista pelo proprietário (derivada da reserva — fonte única é o BookingService). */
    public record SolicitacaoView(String id, String reservaId, String veiculoId, String veiculoTitulo, LocatarioResumo locatario,
                                  LocalDate inicio, LocalDate fim, double diaria, String finalidade,
                                  StatusSolicitacao status, java.time.Instant expiraEm) {
    }

    public record ReputacaoRes(Double media, int total) {
    }
}
