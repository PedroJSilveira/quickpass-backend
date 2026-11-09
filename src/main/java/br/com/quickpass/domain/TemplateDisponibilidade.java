package br.com.quickpass.domain;

/** Template do "liberar agenda com um toque" (RF11). tipo: PROXIMO_FIM_DE_SEMANA | SEXTA_A_DOMINGO. */
public record TemplateDisponibilidade(Tipo tipo, int semanas) {
    public enum Tipo { PROXIMO_FIM_DE_SEMANA, SEXTA_A_DOMINGO }
}
