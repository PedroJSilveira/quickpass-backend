package br.com.quickpass.domain;

import java.time.Instant;

public class FotoVistoria {
    public boolean enviada;
    /** Arquivo enviado (baixável pelas partes da reserva e pelo admin). */
    public String arquivoId;
    public Instant tiradaEm;
    /** Localização aproximada opcional (bairro) — nunca lat/long (RNF02). */
    public String localAproximada;
}
