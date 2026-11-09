package br.com.quickpass.domain;

import java.time.Instant;
import java.util.Map;

/** Notificação enviada, com o dado que a justificou (RNF03). */
public class Notificacao {
    public String id;
    public String userId;
    public CanalNotificacao canal;
    public TipoNotificacao tipo;
    public String titulo;
    public String texto;
    public String link;
    public Map<String, Object> motivoDados;
    public Instant enviadaEm;
    public Instant abertaEm;
}
