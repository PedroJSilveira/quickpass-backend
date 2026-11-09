package br.com.quickpass.domain;

/** Preferências do anúncio que melhoram o próximo ciclo (RF17). */
public record PreferenciasAnuncio(int antecedenciaMinimaHoras, int duracaoMinimaDias) {
}
