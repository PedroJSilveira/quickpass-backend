package br.com.quickpass.service;

import br.com.quickpass.config.ClockService;
import br.com.quickpass.domain.Usuario;
import org.springframework.stereotype.Component;

/** Mock: aprova sempre, com CNH válida por 3 anos. */
@Component
public class MockKycProvider implements KycProvider {
    private final ClockService clock;

    public MockKycProvider(ClockService clock) {
        this.clock = clock;
    }

    @Override
    public ResultadoKyc analisar(Usuario usuario) {
        return new ResultadoKyc(true, clock.hoje().plusYears(3));
    }
}
