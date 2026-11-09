package br.com.quickpass.service;

import br.com.quickpass.domain.Usuario;

import java.time.LocalDate;

/**
 * Adapter do provedor externo de KYC/biometria (ponto em aberto §14.3).
 * Troque a implementação quando o provedor real for definido.
 */
public interface KycProvider {
    record ResultadoKyc(boolean aprovado, LocalDate validadeCnh) {
    }

    ResultadoKyc analisar(Usuario usuario);
}
