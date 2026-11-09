package br.com.quickpass.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.EnumMap;
import java.util.Map;

public class Kyc {
    public boolean doc, cnh, selfie, banco, aprovado;
    /** Último arquivo enviado por item (não exposto na API; o admin baixa por /api/arquivos). */
    @JsonIgnore public Map<ItemKyc, String> arquivos = new EnumMap<>(ItemKyc.class);

    public boolean item(ItemKyc i) {
        return switch (i) { case DOC -> doc; case CNH -> cnh; case SELFIE -> selfie; case BANCO -> banco; };
    }

    public void marcar(ItemKyc i) {
        switch (i) { case DOC -> doc = true; case CNH -> cnh = true; case SELFIE -> selfie = true; case BANCO -> banco = true; }
    }

    public int concluidos() {
        return (doc ? 1 : 0) + (cnh ? 1 : 0) + (selfie ? 1 : 0) + (banco ? 1 : 0);
    }
}
