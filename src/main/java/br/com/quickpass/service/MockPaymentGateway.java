package br.com.quickpass.service;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Mock do gateway. Falhas podem ser forçadas pelo número do cartão (0000… recusa
 * a cobrança; 0001… recusa a pré-autorização da caução) ou pelas flags
 * (POST /api/admin/simulacao/gateway).
 */
@Component
public class MockPaymentGateway implements PaymentGateway {
    public volatile boolean falharCobranca;
    public volatile boolean falharPreAutorizacao;

    @Override
    public Resultado cobrar(String reservaId, double valor, String cartao) {
        boolean recusa = falharCobranca || (cartao != null && cartao.startsWith("0000"));
        return recusa ? Resultado.falha("Pagamento recusado") : Resultado.sucesso();
    }

    @Override
    public Resultado preAutorizar(String reservaId, double valor, Instant validaAte, String cartao) {
        boolean recusa = falharPreAutorizacao || (cartao != null && cartao.startsWith("0001"));
        return recusa ? Resultado.falha("Limite insuficiente para a caução") : Resultado.sucesso();
    }

    @Override
    public Resultado capturar(String reservaId, double valor) {
        return Resultado.sucesso();
    }

    @Override
    public Resultado liberar(String reservaId) {
        return Resultado.sucesso();
    }

    @Override
    public Resultado estornar(String reservaId, double valor) {
        return Resultado.sucesso();
    }
}
