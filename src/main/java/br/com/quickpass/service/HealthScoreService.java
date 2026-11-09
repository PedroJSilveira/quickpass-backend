package br.com.quickpass.service;

import br.com.quickpass.config.Store;
import br.com.quickpass.domain.Papel;
import br.com.quickpass.domain.StatusReserva;
import org.springframework.stereotype.Service;

/**
 * Health Score de confiança (0–100) a partir de reputação publicada e de locações concluídas.
 * NÃO considera indicações (RF10): reputação de confiança não cresce por indicar amigos.
 */
@Service
public class HealthScoreService {
    private final Store store;
    private final ReviewService reviews;

    public HealthScoreService(Store store, ReviewService reviews) {
        this.store = store;
        this.reviews = reviews;
    }

    public int calcular(String userId, Papel papel) {
        Double media = papel == Papel.PROPRIETARIO ? reviews.reputacaoDoProprietario(userId).media() : reviews.reputacaoDoLocatario(userId).media();
        long concluidas = store.reservas.values().stream().filter(r -> r.status == StatusReserva.CONCLUIDA).filter(r -> {
            if (papel == Papel.PROPRIETARIO) {
                var v = store.veiculos.get(r.veiculoId);
                return v != null && v.proprietarioId.equals(userId);
            }
            return r.locatarioId.equals(userId);
        }).count();
        double notaPct = media == null ? 50 : media / 5 * 100;
        return (int) Math.round(Math.min(100, notaPct * 0.8 + Math.min(concluidas, 10) * 2));
    }
}
