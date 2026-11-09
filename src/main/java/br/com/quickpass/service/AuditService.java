package br.com.quickpass.service;

import br.com.quickpass.config.ClockService;
import br.com.quickpass.config.Store;
import br.com.quickpass.domain.Auditoria;
import br.com.quickpass.domain.EntidadeAuditoria;
import org.springframework.stereotype.Service;

import java.util.List;

/** Trilha de auditoria de transições de reserva, caução, disputa, pagamento e KYC (RNF02). */
@Service
public class AuditService {
    private final Store store;
    private final ClockService clock;

    public AuditService(Store store, ClockService clock) {
        this.store = store;
        this.clock = clock;
    }

    public void registrar(EntidadeAuditoria entidade, String id, Object de, Object para, String ator, String motivo) {
        store.auditoria.add(new Auditoria(entidade, id, de == null ? null : de.toString(), para.toString(), ator, clock.now(), motivo));
    }

    public List<Auditoria> daEntidade(EntidadeAuditoria entidade, String id) {
        return store.auditoria.stream().filter(a -> a.entidade() == entidade && a.entidadeId().equals(id)).toList();
    }
}
