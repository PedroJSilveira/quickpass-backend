package br.com.quickpass.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class Usuario {
    public String id;
    public String nome;
    public String email;
    @JsonIgnore public String senhaHash;
    public String cpf;
    public Set<Papel> papeis = EnumSet.noneOf(Papel.class);
    public Kyc kyc = new Kyc();
    public StatusKyc statusKyc = StatusKyc.PENDENTE;
    public double nota = 5.0;
    public int cnhAnos;
    public LocalDate membroDesde;
    /** Validade da CNH; o KYC expira junto (RF02). */
    public LocalDate validadeCnh;
    public Atribuicao origem;
    public String codigoIndicacao;
    @JsonIgnore public Instant kycIniciadoEm;
    public boolean ativo = true;
    /** Consentimento por canal × finalidade (RNF03): marketing nasce desligado. */
    public Map<CanalNotificacao, Map<FinalidadeConsentimento, Boolean>> consentimentos = consentimentosPadrao();

    public static Map<CanalNotificacao, Map<FinalidadeConsentimento, Boolean>> consentimentosPadrao() {
        var m = new EnumMap<CanalNotificacao, Map<FinalidadeConsentimento, Boolean>>(CanalNotificacao.class);
        for (var c : CanalNotificacao.values()) {
            var f = new EnumMap<FinalidadeConsentimento, Boolean>(FinalidadeConsentimento.class);
            f.put(FinalidadeConsentimento.MARKETING, false);
            f.put(FinalidadeConsentimento.TRANSACIONAL, true);
            m.put(c, f);
        }
        return m;
    }

    public boolean permite(CanalNotificacao canal, FinalidadeConsentimento finalidade) {
        return Boolean.TRUE.equals(consentimentos.get(canal).get(finalidade));
    }

    public boolean temPapel(Papel p) {
        return papeis.contains(p);
    }
}
