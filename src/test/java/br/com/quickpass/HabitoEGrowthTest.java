package br.com.quickpass;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HabitoEGrowthTest extends ApiTestBase {

    private Map<String, Object> busca(boolean alertas, boolean consentimento) {
        LocalDate ini = hoje().plusDays(12);
        return Map.of("regiao", "Centro", "raioKm", 10, "inicio", ini.toString(), "fim", ini.plusDays(2).toString(),
                "categoria", "Todos", "alertasAtivos", alertas, "canalConsentido", consentimento);
    }

    @Test
    void radarAlertaSoComConsentimentoESemRepetir() throws Exception {
        String comConsent = novoUsuario("radar1@teste.com", List.of("LOCATARIO"), true, Map.of());
        String semConsent = novoUsuario("radar2@teste.com", List.of("LOCATARIO"), true, Map.of());
        call(HttpMethod.POST, "/api/buscas-salvas", comConsent, busca(true, true), 201);
        call(HttpMethod.POST, "/api/buscas-salvas", semConsent, busca(true, false), 201);

        jobs();
        JsonNode notifs = get("/api/notificacoes", comConsent);
        assertTrue(notifs.size() >= 1);
        JsonNode n = notifs.get(0);
        assertEquals("RADAR", n.get("tipo").asText());
        assertFalse(n.get("motivoDados").isEmpty()); // sempre registra o dado que justificou o envio
        assertEquals(0, get("/api/notificacoes", semConsent).size()); // sem consentimento, nada é enviado

        int antes = notifs.size();
        jobs();
        assertEquals(antes, get("/api/notificacoes", comConsent).size()); // mesmo veículo não repete

        // busca pausada não alerta
        JsonNode lista = get("/api/buscas-salvas", comConsent);
        call(HttpMethod.PATCH, "/api/buscas-salvas/" + lista.get(0).get("id").asText() + "/pausa?pausada=true", comConsent, null, 200);
    }

    @Test
    void retornoEspontaneoXNotificado() throws Exception {
        String renter = novoUsuario("sessao@teste.com", List.of("LOCATARIO"), true, Map.of());
        call(HttpMethod.POST, "/api/buscas-salvas", renter, busca(true, true), 201);
        jobs();
        String nid = get("/api/notificacoes", renter).get(0).get("id").asText();
        post("/api/notificacoes/" + nid + "/abrir", renter, null);
        assertNotNull(get("/api/notificacoes", renter).get(0).get("abertaEm").asText(null));

        call(HttpMethod.POST, "/api/me/sessao", renter, null, 204); // logo após abrir a notificação: retorno NOTIFICADO
        avancarHoras(2);
        call(HttpMethod.POST, "/api/me/sessao", renter, null, 204); // fora da janela de atribuição: ESPONTÂNEO
        String uid = get("/api/me", renter).get("id").asText();
        var sessoes = new java.util.ArrayList<JsonNode>();
        get("/api/admin/eventos?nome=sessao_iniciada", admin()).forEach(e -> {
            if (uid.equals(e.get("userId").asText())) sessoes.add(e);
        });
        assertEquals(2, sessoes.size());
        assertFalse(sessoes.get(0).get("props").get("retorno_espontaneo").asBoolean());
        assertTrue(sessoes.get(1).get("props").get("retorno_espontaneo").asBoolean());
    }

    @Test
    void demandaLocalSoComEvidenciaEConsentimento() throws Exception {
        String p1 = login("p1@example.com", "senha123"); // Centro: 24 buscas na semente (≥ mínimo 10)
        String p2 = login("p2@example.com", "senha123"); // Zona Sul: 12 buscas, mas só 10 é o mínimo... ajustado abaixo
        jobs();
        assertEquals(0, get("/api/notificacoes", p1).size()); // sem consentimento de marketing

        call(HttpMethod.PUT, "/api/me/consentimentos", p1, Map.of("canal", "PUSH", "finalidade", "MARKETING", "valor", true), 200);
        jobs();
        JsonNode n = get("/api/notificacoes", p1);
        assertEquals("DEMANDA_LOCAL", n.get(0).get("tipo").asText());
        assertEquals(1, n.size());
        jobs();
        assertEquals(1, get("/api/notificacoes", p1).size()); // no máximo 1 aviso por semana

        // abaixo do mínimo configurado não envia nada
        call(HttpMethod.PUT, "/api/admin/config/MIN_BUSCAS_DEMANDA", admin(), Map.of("valor", 50), 200);
        call(HttpMethod.PUT, "/api/me/consentimentos", p2, Map.of("canal", "PUSH", "finalidade", "MARKETING", "valor", true), 200);
        jobs();
        assertEquals(0, get("/api/notificacoes", p2).size());

        // transacional não pode ser desligado
        call(HttpMethod.PUT, "/api/me/consentimentos", p1, Map.of("canal", "PUSH", "finalidade", "TRANSACIONAL", "valor", false), 400);
    }

    @Test
    void lembreteDeAgendaSoParaVeiculoRealmenteSemOferta() throws Exception {
        String p2 = login("p2@example.com", "senha123");
        call(HttpMethod.PUT, "/api/me/consentimentos", p2, Map.of("canal", "EMAIL", "finalidade", "MARKETING", "valor", true), 200);
        call(HttpMethod.PUT, "/api/admin/config/MIN_BUSCAS_DEMANDA", admin(), Map.of("valor", 1000), 200); // isola o lembrete do alerta de demanda

        // agenda aberta: nunca vira lembrete, mesmo com o tempo
        avancarHoras(24 * 20);
        assertEquals(0, get("/api/notificacoes", p2).size());

        // fecha todos os dias do horizonte → começa a contar "sem oferta"
        LocalDate hoje = hoje().plusDays(20);
        java.util.Map<String, Boolean> dias = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 20; i++) dias.put(hoje.plusDays(i).toString(), false);
        call(HttpMethod.PUT, "/api/proprietario/veiculos/2/disponibilidade", p2, Map.of("dias", dias, "aceiteAutomatico", false), 200);
        jobs();
        assertEquals(0, get("/api/notificacoes", p2).size()); // ainda não passou DIAS_SEM_OFERTA
        avancarHoras(24 * 16);
        JsonNode n = get("/api/notificacoes", p2);
        assertEquals(1, n.size());
        assertEquals("REVISAO_AGENDA", n.get(0).get("tipo").asText());
        assertTrue(n.get(0).get("motivoDados").get("dias_sem_oferta").asInt() >= 15);

        // liberar datas encerra a régua: a oferta volta no próximo job e o contador de "sem oferta" zera
        call(HttpMethod.POST, "/api/proprietario/veiculos/2/disponibilidade/dias/" + hoje.plusDays(18) + "/alternar", p2, null, 200);
        jobs();
        avancarHoras(24);
        assertEquals(1, get("/api/notificacoes", p2).size());
    }

    @Test
    void indicacaoSoQualificaNaPrimeiraLocacaoPagaEHealthScoreIgnoraIndicacoes() throws Exception {
        String indicador = login("mariana.souza@example.com", "senha123");
        String codigo = get("/api/indicacoes/link", indicador).get("codigo").asText();
        assertTrue(get("/api/indicacoes/link", indicador).get("url").asText().endsWith("?ref=" + codigo));

        Map<String, Object> atrib = Map.of("atribuicao", Map.of("ref", codigo, "utmSource", "amigo"));
        String convidado = novoUsuario("convidado@teste.com", List.of("LOCATARIO"), true, atrib);
        assertEquals("REFERRAL", get("/api/me", convidado).get("origem").get("canal").asText());
        JsonNode ind = get("/api/indicacoes", indicador);
        assertEquals(1, ind.size());
        assertEquals("CADASTRADA", ind.get(0).get("status").asText());

        // locação completa do convidado → qualificada
        LocalDate ini = hoje().plusDays(2);
        String id = reservaConfirmada(convidado, ini, ini.plusDays(1));
        String dono = login("p1@example.com", "senha123");
        avancarHoras(24 * 2);
        vistoria(id, "CHECKIN", convidado, dono, false);
        vistoria(id, "CHECKOUT", convidado, dono, false);
        assertEquals("CADASTRADA", get("/api/indicacoes", indicador).get(0).get("status").asText()); // caução ainda não resolvida
        avancarHoras(25);
        assertEquals("QUALIFICADA", get("/api/indicacoes", indicador).get(0).get("status").asText());

        // código inexistente não registra indicação nem quebra o cadastro
        novoUsuario("convidado2@teste.com", List.of("LOCATARIO"), false, Map.of("atribuicao", Map.of("ref", "XXXX")));
        assertEquals(1, get("/api/indicacoes", indicador).size());
        int score = get("/api/me/health-score?papel=LOCATARIO", indicador).get("score").asInt();
        assertTrue(score >= 0 && score <= 100);
    }

    @Test
    void atribuicaoFirstTouchEFunilPorCanal() throws Exception {
        novoUsuario("paga@teste.com", List.of("LOCATARIO"), false, Map.of("atribuicao", Map.of("utmSource", "g", "utmMedium", "cpc", "utmCampaign", "c1")));
        novoUsuario("local@teste.com", List.of("PROPRIETARIO"), false, Map.of("atribuicao", Map.of("utmMedium", "hiperlocal")));
        novoUsuario("direto@teste.com", List.of("LOCATARIO"), false, Map.of());
        JsonNode funil = get("/api/admin/metricas/funil", admin());
        var canais = funil.findValuesAsText("canal");
        assertTrue(canais.containsAll(List.of("midia_paga", "hiperlocal", "direto")), canais.toString());
    }

    @Test
    void experimentoRf09IsentaTaxaSoNaPrimeiraLocacaoDoGrupoTratamento() throws Exception {
        // força o sorteio: com 100% em tratamento, a 1ª locação do proprietário novo tem taxa isenta
        call(HttpMethod.PUT, "/api/admin/config/EXP_RF09_PCT_TRATAMENTO", admin(), Map.of("valor", 100), 200);
        String renter = novoUsuario("exp@teste.com", List.of("LOCATARIO"), true, Map.of());
        LocalDate ini = hoje().plusDays(2);
        String id = reservaConfirmada(renter, ini, ini.plusDays(1));
        JsonNode pag = get("/api/reservas/" + id, renter).get("pagamento");
        assertTrue(pag.get("desconto").asDouble() > 0);
        assertEquals(pag.get("taxaPlataforma").asDouble(), pag.get("desconto").asDouble());

        // a segunda reserva do mesmo proprietário não recebe desconto
        LocalDate ini2 = hoje().plusDays(6);
        String id2 = reservaConfirmada(renter, ini2, ini2.plusDays(1));
        assertEquals(0.0, get("/api/reservas/" + id2, renter).get("pagamento").get("desconto").asDouble());
        JsonNode exp = get("/api/admin/experimento", admin());
        assertEquals("TRATAMENTO", exp.get("resultado").get("linhas").get(0).get("grupo").asText());
        assertTrue(exp.get("estado").get("gasto").asDouble() > 0);
    }
}
