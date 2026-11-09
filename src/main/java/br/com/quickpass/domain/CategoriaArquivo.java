package br.com.quickpass.domain;

/** Para que serve o arquivo enviado; define quem pode baixá-lo. */
public enum CategoriaArquivo {
    KYC_DOC, KYC_CNH, KYC_SELFIE, KYC_BANCO,
    VEICULO_FOTO, VEICULO_DOCUMENTO,
    VISTORIA_FOTO, AVARIA_FOTO, CONTESTACAO_EVIDENCIA;

    /** Só as fotos do anúncio são públicas (aparecem na busca); o resto é restrito às partes e ao admin. */
    public boolean publica() {
        return this == VEICULO_FOTO;
    }
}
