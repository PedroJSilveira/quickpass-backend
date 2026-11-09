package br.com.quickpass.service;

import java.time.Instant;

/** Adapter do gateway de pagamento com pré-autorização (ponto em aberto §14.3). */
public interface PaymentGateway {
    record Resultado(boolean ok, String motivo) {
        public static Resultado sucesso() {
            return new Resultado(true, null);
        }

        public static Resultado falha(String motivo) {
            return new Resultado(false, motivo);
        }
    }

    Resultado cobrar(String reservaId, double valor, String cartao);

    Resultado preAutorizar(String reservaId, double valor, Instant validaAte, String cartao);

    Resultado capturar(String reservaId, double valor);

    Resultado liberar(String reservaId);

    Resultado estornar(String reservaId, double valor);
}
