package br.com.quickpass.service;

import br.com.quickpass.common.ApiException;
import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Parametro;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.*;
import br.com.quickpass.web.Dtos.AvaliacaoReq;
import br.com.quickpass.web.Dtos.ReputacaoRes;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static br.com.quickpass.common.Util.props;

/**
 * Avaliação bilateral (RF06). Opcional; ocultas até as duas partes enviarem OU expirar
 * PRAZO_AVALIACAO_HORAS após a conclusão. Quem não avalia não é penalizado.
 */
@Service
public class ReviewService {
    private final Store store;
    private final AppConfigService config;
    private final ClockService clock;
    private final EventBus bus;

    public ReviewService(Store store, AppConfigService config, ClockService clock, EventBus bus) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.bus = bus;
    }

    public List<Avaliacao> publicadas() {
        return store.avaliacoes.stream().filter(a -> a.publicadaEm != null).toList();
    }

    public List<Avaliacao> publicadasDoVeiculo(String veiculoId) {
        return publicadas().stream()
                .filter(a -> a.autorPapel == Papel.LOCATARIO)
                .filter(a -> {
                    Reserva r = store.reservas.get(a.reservaId);
                    return r != null && r.veiculoId.equals(veiculoId);
                }).toList();
    }

    /** Avaliações da reserva visíveis ao usuário: as publicadas + as que ele próprio enviou. */
    public List<Avaliacao> daReserva(String reservaId, Usuario u) {
        return store.avaliacoes.stream()
                .filter(a -> a.reservaId.equals(reservaId) && (a.publicadaEm != null || a.autorId.equals(u.id))).toList();
    }

    public Avaliacao enviar(Usuario autor, String reservaId, AvaliacaoReq req) {
        Reserva r = store.reservas.get(reservaId);
        if (r == null) throw ApiException.naoEncontrado("Reserva não encontrada.");
        Veiculo v = store.veiculos.get(r.veiculoId);
        Papel papel;
        String alvo;
        if (autor.id.equals(r.locatarioId)) {
            papel = Papel.LOCATARIO;
            alvo = v.proprietarioId;
        } else if (autor.id.equals(v.proprietarioId)) {
            papel = Papel.PROPRIETARIO;
            alvo = r.locatarioId;
        } else {
            throw ApiException.proibido("Você não participa desta locação.");
        }
        if (r.status != StatusReserva.CONCLUIDA) throw ApiException.regra("Só é possível avaliar uma locação concluída.");
        if (store.avaliacoes.stream().anyMatch(a -> a.reservaId.equals(reservaId) && a.autorPapel == papel)) {
            throw ApiException.conflito("Esta parte já avaliou a locação.");
        }
        Avaliacao a = new Avaliacao();
        a.reservaId = reservaId;
        a.autorId = autor.id;
        a.autorPapel = papel;
        a.alvoId = alvo;
        a.nota = req.nota();
        a.texto = req.texto() == null ? "" : req.texto();
        a.selos = req.selos() == null ? List.of() : req.selos();
        a.enviadaEm = clock.now();
        store.avaliacoes.add(a);
        if (papel == Papel.LOCATARIO) r.avaliada = true;
        bus.emitir("avaliacao_enviada", props("reservation_id", reservaId, "autor_papel", papel.name().toLowerCase(), "nota", a.nota));
        if (store.avaliacoes.stream().filter(x -> x.reservaId.equals(reservaId)).count() >= 2) publicar(reservaId);
        return a;
    }

    private void publicar(String reservaId) {
        boolean mudou = false;
        for (Avaliacao a : store.avaliacoes) {
            if (a.reservaId.equals(reservaId) && a.publicadaEm == null) {
                a.publicadaEm = clock.now();
                mudou = true;
            }
        }
        if (mudou) bus.emitir("avaliacoes_publicadas", props("reservation_id", reservaId));
    }

    /** Job: prazo vencido → publica só o que existir (nada, se ninguém avaliou). */
    public void publicarVencidas() {
        long prazoH = config.getInt(Parametro.PRAZO_AVALIACAO_HORAS);
        Set<String> pendentes = store.avaliacoes.stream().filter(a -> a.publicadaEm == null).map(a -> a.reservaId).collect(Collectors.toSet());
        for (String id : pendentes) {
            Reserva r = store.reservas.get(id);
            var base = r == null ? null : (r.concluidaEm != null ? r.concluidaEm : r.criadaEm);
            if (base != null && Duration.between(base, clock.now()).toHours() >= prazoH) publicar(id);
        }
    }

    // ───────────── reputação (ranking, aprovação automática e Health Score) ─────────────

    public ReputacaoRes reputacaoDoVeiculo(String veiculoId) {
        return media(publicadasDoVeiculo(veiculoId).stream().mapToInt(a -> a.nota).toArray());
    }

    /** Média das avaliações publicadas recebidas por um locatário (feitas por proprietários). */
    public ReputacaoRes reputacaoDoLocatario(String locatarioId) {
        return media(publicadas().stream().filter(a -> a.autorPapel == Papel.PROPRIETARIO && a.alvoId.equals(locatarioId)).mapToInt(a -> a.nota).toArray());
    }

    /** Média das avaliações publicadas recebidas por um proprietário (feitas por locatários). */
    public ReputacaoRes reputacaoDoProprietario(String proprietarioId) {
        return media(publicadas().stream().filter(a -> a.autorPapel == Papel.LOCATARIO && a.alvoId.equals(proprietarioId)).mapToInt(a -> a.nota).toArray());
    }

    /** Nota usada no ranking: avaliações publicadas se houver; senão a nota cadastrada do veículo. */
    public double notaParaRanking(String veiculoId) {
        Double m = reputacaoDoVeiculo(veiculoId).media();
        if (m != null) return m;
        Veiculo v = store.veiculos.get(veiculoId);
        return v == null || v.nota == null ? 0 : v.nota;
    }

    private ReputacaoRes media(int[] notas) {
        if (notas.length == 0) return new ReputacaoRes(null, 0);
        double soma = 0;
        for (int n : notas) soma += n;
        return new ReputacaoRes(soma / notas.length, notas.length);
    }
}
