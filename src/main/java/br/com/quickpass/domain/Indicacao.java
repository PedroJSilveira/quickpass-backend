package br.com.quickpass.domain;

import java.time.Instant;

public class Indicacao {
    public String referrerId;
    public String inviteeId;
    public String codigo;
    public StatusIndicacao status = StatusIndicacao.CADASTRADA;
    public Instant qualificadaEm;
    public double bonus;
}
