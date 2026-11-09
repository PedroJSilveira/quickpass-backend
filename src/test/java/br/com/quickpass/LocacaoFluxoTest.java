package br.com.quickpass;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocacaoFluxoTest extends ApiTestBase {

    @Test
    void locacaoCompletaAteRepasseEAvaliacao() throws Exception {
        String renter = novoUsuario("locatario1@teste.com", List.of("LOCATARIO"), true, Map.of());
        String dono = login("p1@example.com", "senha123");
        LocalDate ini = hoje().plusDays(10), fim = ini.plusDays(2);

        // busca: o veículo 1 aparece, sem placa nem endereço exato
        JsonNode busca = get("/api/veiculos?local=Centro&inicio=" + ini + "&fim=" + fim, renter);
        assertTrue(busca.findValuesAsText("id").contains("1"));
        assertFalse(busca.get(0).has("placa"));
        assertFalse(busca.get(0).has("enderecoExato"));

        String id = reservaConfirmada(renter, ini, fim);
        assertEquals("Rua Augusta, 1200 – Centro", get("/api/reservas/" + id + "/endereco", renter).get("enderecoExato").asText());
        assertEquals("CONFIRMADA", get("/api/reservas/" + id, dono).get("reserva").get("status").asText());

        // check-in só dentro da janela
        call(HttpMethod.POST, "/api/reservas/" + id + "/vistorias/CHECKIN/concluir", renter, Map.of("avaria", false), 400);
        avancarHoras(24 * 10);
        assertEquals("EM_ANDAMENTO", vistoria(id, "CHECKIN", renter, dono, false).get("status").asText());
        assertEquals("CONCLUIDA", vistoria(id, "CHECKOUT", renter, dono, false).get("status").asText());

        // repasse só depois da liberação da caução (24h)
        assertEquals("PREVISTO", get("/api/proprietario/ganhos", dono).get("repasses").get(0).get("status").asText());
        avancarHoras(25);
        JsonNode ganhos = get("/api/proprietario/ganhos", dono);
        assertEquals("PAGO", ganhos.get("repasses").get(0).get("status").asText());
        assertTrue(ganhos.get("liquidoMesAtual").asDouble() > 0);
        assertEquals("LIBERADA", get("/api/reservas/" + id, renter).get("caucao").get("status").asText());

        // avaliação bilateral: só publica quando as duas partes avaliam
        call(HttpMethod.POST, "/api/reservas/" + id + "/avaliacoes", renter, Map.of("nota", 5, "texto", "Ótimo"), 201);
        assertEquals(0, get("/api/veiculos/1/avaliacoes", null).get("avaliacoes").size());
        call(HttpMethod.POST, "/api/reservas/" + id + "/avaliacoes", dono, Map.of("nota", 4, "texto", "Ok"), 201);
        assertEquals(1, get("/api/veiculos/1/avaliacoes", null).get("avaliacoes").size());
        call(HttpMethod.POST, "/api/reservas/" + id + "/avaliacoes", renter, Map.of("nota", 5), 409);

        // North Star
        JsonNode metricas = get("/api/admin/metricas", admin());
        assertEquals(1, metricas.get("northStar").get(0).get("locacoes").asInt());
    }

    @Test
    void avariaGeraDisputaECapturaParcialDaCaucao() throws Exception {
        String renter = novoUsuario("locatario2@teste.com", List.of("LOCATARIO"), true, Map.of());
        String dono = login("p1@example.com", "senha123");
        String admin = admin();
        LocalDate ini = hoje().plusDays(3);
        String id = reservaConfirmada(renter, ini, ini.plusDays(1));
        avancarHoras(24 * 3);
        vistoria(id, "CHECKIN", renter, dono, false);
        assertEquals("EM_DISPUTA", vistoria(id, "CHECKOUT", renter, dono, true).get("status").asText());

        JsonNode d = call(HttpMethod.POST, "/api/reservas/" + id + "/avaria", dono,
                Map.of("descricao", "Risco na porta", "qtdFotos", 2, "valorEstimado", 300), 201);
        String did = d.get("id").asText();
        assertEquals("AGUARDANDO_CONTESTACAO", d.get("status").asText());
        assertEquals("RETIDA_EM_DISPUTA", get("/api/reservas/" + id, renter).get("caucao").get("status").asText());
        // locatário é notificado (transacional)
        assertEquals("AVARIA_INFORMADA", get("/api/notificacoes", renter).get(0).get("tipo").asText());

        // mediador não decide antes da contestação / análise
        call(HttpMethod.POST, "/api/admin/disputas/" + did + "/analise", admin, null, 400);
        post("/api/disputas/" + did + "/contestacao", renter, Map.of("texto", "Já estava assim", "qtdEvidencias", 1));
        post("/api/admin/disputas/" + did + "/analise", admin, null);

        // captura parcial inválida é rejeitada; válida conclui a reserva
        call(HttpMethod.POST, "/api/admin/disputas/" + did + "/decisao", admin,
                Map.of("tipo", "CAPTURA_PARCIAL", "valor", 500, "justificativa", "x"), 400);
        JsonNode dec = post("/api/admin/disputas/" + did + "/decisao", admin, Map.of("tipo", "CAPTURA_PARCIAL", "valor", 200, "justificativa", "Dano comprovado"));
        assertEquals("DECIDIDA", dec.get("status").asText());
        JsonNode det = get("/api/reservas/" + id, renter);
        assertEquals("CONCLUIDA", det.get("reserva").get("status").asText());
        assertEquals("CAPTURADA_PARCIAL", det.get("caucao").get("status").asText());
        assertEquals(200.0, det.get("caucao").get("valorCapturado").asDouble());

        // locatário não pode decidir disputa
        call(HttpMethod.POST, "/api/admin/disputas/" + did + "/decisao", renter, Map.of("tipo", "LIBERACAO", "valor", 0, "justificativa", "x"), 403);
        jobs();
        assertEquals("PAGO", get("/api/proprietario/ganhos", dono).get("repasses").get(0).get("status").asText());
    }

    @Test
    void falhaDePagamentoOuCaucaoNaoConfirmaAReserva() throws Exception {
        String renter = novoUsuario("locatario3@teste.com", List.of("LOCATARIO"), true, Map.of());
        String dono = login("p1@example.com", "senha123");
        LocalDate ini = hoje().plusDays(5);
        for (String cartao : List.of("0000111122223333", "0001111122223333")) {
            String id = call(HttpMethod.POST, "/api/reservas", renter,
                    Map.of("veiculoId", "1", "inicio", ini.toString(), "fim", ini.plusDays(1).toString(), "protecao", "COMPLETA"), 201).get("id").asText();
            post("/api/reservas/" + id + "/aprovar", dono, null);
            JsonNode r = post("/api/reservas/" + id + "/pagar", renter, Map.of("metodo", "cartao", "numeroCartao", cartao));
            assertEquals("FALHA_PAGAMENTO", r.get("status").asText());
        }
        // a agenda foi liberada: nova solicitação nas mesmas datas é possível
        call(HttpMethod.POST, "/api/reservas", renter, Map.of("veiculoId", "1", "inicio", ini.toString(), "fim", ini.plusDays(1).toString(), "protecao", "BASICA"), 201);
    }

    @Test
    void cancelamentoConfirmadoEstornaConformePolitica() throws Exception {
        String renter = novoUsuario("locatario4@teste.com", List.of("LOCATARIO"), true, Map.of());
        LocalDate ini = hoje().plusDays(10);
        String id = reservaConfirmada(renter, ini, ini.plusDays(2));
        JsonNode r = post("/api/reservas/" + id + "/cancelar", renter, Map.of("motivo", "mudança de planos"));
        assertEquals("CANCELADA", r.get("status").asText());
        JsonNode det = get("/api/reservas/" + id, renter);
        assertEquals("ESTORNADO_TOTAL", det.get("pagamento").get("status").asText()); // > 48h de antecedência
        assertEquals("LIBERADA", det.get("caucao").get("status").asText());
        call(HttpMethod.POST, "/api/reservas/" + id + "/cancelar", renter, null, 409); // já cancelada
    }
}
