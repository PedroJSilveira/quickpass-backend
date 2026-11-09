package br.com.quickpass.domain;

import java.time.Instant;
import java.time.LocalDate;

public record BuscaRegistrada(String regiao, String categoria, LocalDate inicio, LocalDate fim, Instant em) {
}
