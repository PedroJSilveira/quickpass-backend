package br.com.quickpass.web;

import br.com.quickpass.config.AppConfigService;
import br.com.quickpass.service.PricingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parâmetros que a interface precisa exibir (prazos, preços, limites). Públicos e somente leitura. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {
    public record Precos(double taxaPct, double caucao, double protecaoBasicaDia, double protecaoCompletaDia, double franquiaBasica, double franquiaCompleta) {
    }

    private final AppConfigService config;

    public ConfigController(AppConfigService config) {
        this.config = config;
    }

    @GetMapping("/publica")
    public Map<String, Object> publica() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parametros", config.todos());
        m.put("precos", new Precos(PricingService.TAXA_PCT, PricingService.CAUCAO, PricingService.PROTECAO_BASICA_DIA,
                PricingService.PROTECAO_COMPLETA_DIA, PricingService.FRANQUIA_BASICA, PricingService.FRANQUIA_COMPLETA));
        return m;
    }
}
