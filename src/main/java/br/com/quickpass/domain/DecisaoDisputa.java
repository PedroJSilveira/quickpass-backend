package br.com.quickpass.domain;

import java.time.Instant;

public record DecisaoDisputa(TipoDecisao tipo, double valor, String justificativa, String decididaPor, Instant decididaEm) {
}
