package br.com.quickpass.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public class Vistoria {
    public String reservaId;
    public TipoVistoria tipo;
    public Map<FotoChave, FotoVistoria> fotos = new EnumMap<>(FotoChave.class);
    public Integer km;
    public String combustivel = "3/4";
    public String observacoes = "";
    public Instant criadaEm;
    public boolean aceiteLocatario;
    public boolean aceiteProprietario;
    public boolean concluida;

    public long fotosEnviadas() {
        return fotos.values().stream().filter(f -> f.enviada).count();
    }
}
