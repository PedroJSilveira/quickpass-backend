package br.com.quickpass.service;

import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.Repasse;
import br.com.quickpass.domain.StatusRepasse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

import static br.com.quickpass.common.Util.r2;

/**
 * Repasses ao proprietário. Aparece como PREVISTO quando a reserva entra em andamento e só é
 * pago após CONCLUIDA + caução resolvida (orquestração feita pelo BookingService).
 */
@Service
public class PayoutService {
    private final Store store;
    private final ClockService clock;

    public PayoutService(Store store, ClockService clock) {
        this.store = store;
        this.clock = clock;
    }

    public Repasse daReserva(String reservaId) {
        return store.repasses.stream().filter(r -> r.reservaId.equals(reservaId)).findFirst().orElse(null);
    }

    public List<Repasse> doProprietario(String proprietarioId) {
        return store.repasses.stream().filter(r -> r.proprietarioId.equals(proprietarioId)).toList();
    }

    /** Líquido já pago no mês corrente. */
    public double liquidoMesAtual(String proprietarioId) {
        LocalDate hoje = clock.hoje();
        return r2(doProprietario(proprietarioId).stream()
                .filter(r -> r.status == StatusRepasse.PAGO && r.pagoEm != null && r.pagoEm.getYear() == hoje.getYear() && r.pagoEm.getMonth() == hoje.getMonth())
                .mapToDouble(r -> r.liquido).sum());
    }

    public double saldoPrevisto(String proprietarioId) {
        return r2(doProprietario(proprietarioId).stream().filter(r -> r.status == StatusRepasse.PREVISTO).mapToDouble(r -> r.liquido).sum());
    }

    public void pagar(String reservaId) {
        Repasse r = daReserva(reservaId);
        if (r == null || r.status == StatusRepasse.PAGO) return;
        r.status = StatusRepasse.PAGO;
        r.pagoEm = clock.hoje();
    }

    public void registrarPrevisto(String reservaId, String veiculoId, String proprietarioId, String titulo, String periodo,
                                  double bruto, double taxaBase, double desconto) {
        if (daReserva(reservaId) != null) return;
        // Desconto do experimento RF09 isenta (parte da) taxa de intermediação.
        double taxa = Math.max(0, r2(taxaBase - desconto));
        Repasse r = new Repasse();
        r.reservaId = reservaId;
        r.veiculoId = veiculoId;
        r.proprietarioId = proprietarioId;
        r.veiculoTitulo = titulo;
        r.periodo = periodo;
        r.bruto = bruto;
        r.taxa = taxa;
        r.liquido = r2(bruto - taxa);
        r.status = StatusRepasse.PREVISTO;
        store.repasses.add(0, r);
    }
}
