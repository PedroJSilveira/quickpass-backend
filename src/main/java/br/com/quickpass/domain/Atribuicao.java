package br.com.quickpass.domain;

import java.time.Instant;

/** Atribuição first-touch gravada no cadastro e imutável (RF08). */
public record Atribuicao(CanalOrigem canal, String utmSource, String utmMedium, String utmCampaign,
                         String utmTerm, String utmContent, String referralCode, Instant capturadaEm) {
}
