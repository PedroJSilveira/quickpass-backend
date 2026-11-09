package br.com.quickpass.domain;

public class Kyc {
    public boolean doc, cnh, selfie, banco, aprovado;

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
