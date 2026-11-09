package br.com.quickpass.domain;

import java.time.Instant;

/** Registro de auditoria (RNF02): quem, quando, de/para. */
public record Auditoria(EntidadeAuditoria entidade, String entidadeId, String de, String para,
                        String ator, Instant em, String motivo) {
}
