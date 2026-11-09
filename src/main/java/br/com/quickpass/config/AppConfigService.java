package br.com.quickpass.config;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Acesso central aos parâmetros da seção 11; podem ser alterados em runtime (sem deploy). */
@Service
public class AppConfigService {
    private final Map<Parametro, Double> sobrescritas = new EnumMap<>(Parametro.class);

    public double get(Parametro p) {
        return sobrescritas.getOrDefault(p, p.padrao);
    }

    public int getInt(Parametro p) {
        return (int) Math.round(get(p));
    }

    public void definir(Parametro p, double valor) {
        sobrescritas.put(p, valor);
    }

    public void restaurar(Parametro p) {
        sobrescritas.remove(p);
    }

    public Map<String, Object> todos() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Parametro p : Parametro.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valor", get(p));
            m.put("padrao", p.padrao);
            m.put("provisorio", p.provisorio);
            out.put(p.name(), m);
        }
        return out;
    }
}
