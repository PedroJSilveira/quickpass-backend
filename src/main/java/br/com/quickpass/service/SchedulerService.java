package br.com.quickpass.service;

import br.com.quickpass.config.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agendador de jobs (expiração de reservas, liberação de caução, repasse, radar, lembretes…).
 * Em produção seriam rotinas dedicadas; aqui um intervalo chama {@link #tick()} sob o lock global.
 * Também pode ser disparado manualmente (POST /api/admin/jobs/executar).
 */
@Service
public class SchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final Store store;
    private final UsuarioService usuarios;
    private final BookingService booking;
    private final DepositService deposit;
    private final DisputeService disputes;
    private final ReviewService reviews;
    private final SavedSearchService radar;
    private final AgendaReminderService lembretes;
    private final LocalDemandAlertService demandaLocal;

    public SchedulerService(Store store, UsuarioService usuarios, BookingService booking, DepositService deposit, DisputeService disputes,
                            ReviewService reviews, SavedSearchService radar, AgendaReminderService lembretes, LocalDemandAlertService demandaLocal) {
        this.store = store;
        this.usuarios = usuarios;
        this.booking = booking;
        this.deposit = deposit;
        this.disputes = disputes;
        this.reviews = reviews;
        this.radar = radar;
        this.lembretes = lembretes;
        this.demandaLocal = demandaLocal;
    }

    @Scheduled(fixedDelayString = "${quickpass.scheduler.intervalo-ms:15000}", initialDelayString = "${quickpass.scheduler.intervalo-ms:15000}")
    public void agendado() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("Falha no tick do agendador", e);
        }
    }

    /** Executa todos os jobs, nesta ordem (cada um é idempotente). */
    public void tick() {
        store.lock.lock();
        try {
            usuarios.verificarExpiracaoTodos();
            booking.expirarPendentes();
            deposit.processarLiberacoes();
            disputes.encerrarPrazosDeContestacao();
            booking.processarRepasses();
            reviews.publicarVencidas();
            radar.verificarMatches();
            lembretes.executar();
            demandaLocal.executar();
        } finally {
            store.lock.unlock();
        }
    }
}
