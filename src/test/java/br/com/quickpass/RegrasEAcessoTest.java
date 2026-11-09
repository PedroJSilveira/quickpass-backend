package br.com.quickpass;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegrasEAcessoTest extends ApiTestBase {

    @Test
    void semKycReservaFicaRetidaEExpiraENaoBloqueiaAgenda() throws Exception {
        String renter = novoUsuario("semkyc@teste.com", List.of("LOCATARIO"), false, Map.of());
        String outro = novoUsuario("outro@teste.com", List.of("LOCATARIO"), true, Map.of());
        LocalDate ini = hoje().plusDays(8);
        Map<String, Object> req = Map.of("veiculoId", "1", "inicio", ini.toString(), "fim", ini.plusDays(2).toString(), "protecao", "BASICA");

        String id = call(HttpMethod.POST, "/api/reservas", renter, req, 201).get("id").asText();
        assertEquals("AGUARDANDO_KYC", get("/api/reservas/" + id, renter).get("reserva").get("status").asText());
        // o hold ocupa a agenda
        call(HttpMethod.POST, "/api/reservas", outro, req, 409);
        // endereço exato não é revelado
        assertTrue(get("/api/reservas/" + id + "/endereco", renter).get("enderecoExato").isNull());

        avancarHoras(1); // hold de KYC = 15 min
        assertEquals("EXPIRADA", get("/api/reservas/" + id, renter).get("reserva").get("status").asText());
        call(HttpMethod.POST, "/api/reservas", outro, req, 201);
    }

    @Test
    void continuarAposKycSegueParaFilaDoProprietario() throws Exception {
        String renter = novoUsuario("kyctarde@teste.com", List.of("LOCATARIO"), false, Map.of());
        LocalDate ini = hoje().plusDays(8);
        String id = call(HttpMethod.POST, "/api/reservas", renter,
                Map.of("veiculoId", "1", "inicio", ini.toString(), "fim", ini.plusDays(1).toString(), "protecao", "BASICA"), 201).get("id").asText();
        call(HttpMethod.POST, "/api/reservas/" + id + "/continuar-kyc", renter, null, 400); // KYC ainda não aprovado
        for (String i : List.of("DOC", "CNH", "SELFIE", "BANCO")) post("/api/me/kyc/" + i, renter, null);
        assertEquals("SOLICITADA", post("/api/reservas/" + id + "/continuar-kyc", renter, null).get("status").asText());
        JsonNode fila = get("/api/proprietario/solicitacoes?status=PENDENTE", login("p1@example.com", "senha123"));
        assertTrue(fila.findValuesAsText("reservaId").contains(id));
    }

    @Test
    void permissoesPorPapel() throws Exception {
        String soDono = novoUsuario("sodono@teste.com", List.of("PROPRIETARIO"), true, Map.of());
        String soLoc = novoUsuario("soloc@teste.com", List.of("LOCATARIO"), true, Map.of());
        LocalDate ini = hoje().plusDays(8);
        Map<String, Object> req = Map.of("veiculoId", "1", "inicio", ini.toString(), "fim", ini.plusDays(1).toString(), "protecao", "BASICA");

        call(HttpMethod.POST, "/api/reservas", null, req, 401);
        call(HttpMethod.POST, "/api/reservas", soDono, req, 403);
        call(HttpMethod.GET, "/api/proprietario/veiculos", soLoc, null, 403);
        call(HttpMethod.GET, "/api/admin/metricas", soLoc, null, 403);
        call(HttpMethod.POST, "/api/auth/cadastro", null, Map.of("nome", "X", "email", "adm@teste.com", "senha", "segredo1", "cpf", "52998224725", "papeis", List.of("ADMIN")), 403);

        // um usuário pode virar locatário E proprietário
        JsonNode u = call(HttpMethod.PUT, "/api/me/papeis", soLoc, Map.of("papeis", List.of("LOCATARIO", "PROPRIETARIO")), 200);
        assertEquals(2, u.get("papeis").size());
        call(HttpMethod.GET, "/api/proprietario/veiculos", soLoc, null, 200);
        call(HttpMethod.PUT, "/api/me/papeis", soLoc, Map.of("papeis", List.of("ADMIN")), 403);

        // proprietário não aprova reserva de veículo alheio
        String locat = novoUsuario("loc9@teste.com", List.of("LOCATARIO"), true, Map.of());
        String id = call(HttpMethod.POST, "/api/reservas", locat, req, 201).get("id").asText();
        call(HttpMethod.POST, "/api/reservas/" + id + "/aprovar", soDono, null, 403);
        call(HttpMethod.GET, "/api/reservas/" + id, soLoc, null, 403);
    }

    @Test
    void proprietarioNaoAlugaOProprioVeiculoENemPublicaAgendaSemKyc() throws Exception {
        String mariana = login("mariana.souza@example.com", "senha123"); // KYC pendente
        LocalDate ini = hoje().plusDays(8);
        call(HttpMethod.POST, "/api/reservas", mariana,
                Map.of("veiculoId", "u1-v1", "inicio", ini.toString(), "fim", ini.plusDays(1).toString(), "protecao", "BASICA"), 400);
        call(HttpMethod.PUT, "/api/proprietario/veiculos/u1-v1/disponibilidade", mariana,
                Map.of("dias", Map.of(ini.toString(), true), "aceiteAutomatico", false), 403);
        // veículo alheio
        call(HttpMethod.GET, "/api/proprietario/veiculos/1", mariana, null, 403);
    }

    @Test
    void cadastroDeVeiculoPassaPorModeracaoDoAdmin() throws Exception {
        String dono = novoUsuario("novodono@teste.com", List.of("PROPRIETARIO"), true, Map.of());
        Map<String, Object> v = new java.util.HashMap<>();
        v.put("marca", "VW"); v.put("modelo", "Polo"); v.put("ano", 2023); v.put("placa", "BRA2E19"); v.put("categoria", "Hatch");
        v.put("diaria", 120); v.put("local", "Centro"); v.put("enderecoExato", "Rua X, 1"); v.put("cambio", "Automático");
        v.put("combustivel", "Flex"); v.put("lugares", 5); v.put("km", 1000);
        JsonNode criado = call(HttpMethod.POST, "/api/proprietario/veiculos", dono, v, 201);
        String id = criado.get("id").asText();
        assertEquals("EM_ANALISE", criado.get("status").asText());
        call(HttpMethod.POST, "/api/proprietario/veiculos", dono, v, 409); // placa duplicada
        v.put("placa", "inválida");
        call(HttpMethod.POST, "/api/proprietario/veiculos", dono, v, 400);

        // fora da busca e do detalhe público até ser aprovado
        assertFalse(get("/api/veiculos", null).findValuesAsText("id").contains(id));
        call(HttpMethod.GET, "/api/veiculos/" + id, null, null, 404);
        post("/api/admin/veiculos/" + id + "/aprovar", admin(), null);
        assertTrue(get("/api/veiculos", null).findValuesAsText("id").contains(id));
        // filtros de categoria/preço
        assertFalse(get("/api/veiculos?categoria=SUV&precoMax=150", null).findValuesAsText("id").contains(id));
        assertTrue(get("/api/veiculos?categoria=Hatch&precoMax=150", null).findValuesAsText("id").contains(id));
    }

    @Test
    void agendaDoProprietarioRestringeABuscaEAUmToqueSeDesfaz() throws Exception {
        String dono = login("p2@example.com", "senha123"); // veículo "2", KYC aprovado
        LocalDate d1 = hoje().plusDays(20), d2 = d1.plusDays(1);
        // com a agenda configurada, só os dias explicitamente liberados contam
        LocalDate d3 = d2.plusDays(1);
        call(HttpMethod.PUT, "/api/proprietario/veiculos/2/disponibilidade", dono,
                Map.of("dias", Map.of(d1.toString(), true, d2.toString(), true, d3.toString(), false), "aceiteAutomatico", false), 200);
        assertTrue(get("/api/veiculos?inicio=" + d1 + "&fim=" + d3, null).findValuesAsText("id").contains("2"));
        assertFalse(get("/api/veiculos?inicio=" + d1 + "&fim=" + d3.plusDays(1), null).findValuesAsText("id").contains("2"));

        // template + um toque + desfazer (RF11)
        call(HttpMethod.PUT, "/api/proprietario/template-disponibilidade", dono, Map.of("tipo", "SEXTA_A_DOMINGO", "semanas", 2), 200);
        JsonNode previa = get("/api/proprietario/veiculos/2/um-toque/previa", dono);
        assertEquals(0, previa.get("motivos").size());
        JsonNode res = post("/api/proprietario/veiculos/2/um-toque", dono, null);
        assertTrue(res.get("adicionados").size() > 0);
        post("/api/proprietario/veiculos/2/um-toque/desfazer", dono, null);
        call(HttpMethod.POST, "/api/proprietario/veiculos/2/um-toque/desfazer", dono, null, 400);
    }

    @Test
    void parametrosSaoConfiguraveisEmRuntime() throws Exception {
        String admin = admin();
        call(HttpMethod.PUT, "/api/admin/config/HOLD_KYC_MINUTOS", admin, Map.of("valor", 120), 200);
        String renter = novoUsuario("cfg@teste.com", List.of("LOCATARIO"), false, Map.of());
        LocalDate ini = hoje().plusDays(8);
        String id = call(HttpMethod.POST, "/api/reservas", renter,
                Map.of("veiculoId", "1", "inicio", ini.toString(), "fim", ini.plusDays(1).toString(), "protecao", "BASICA"), 201).get("id").asText();
        avancarHoras(1); // hold agora é de 120 min: continua retido
        assertEquals("AGUARDANDO_KYC", get("/api/reservas/" + id, renter).get("reserva").get("status").asText());
        call(HttpMethod.PUT, "/api/admin/config/HOLD_KYC_MINUTOS", admin, Map.of("valor", 120), 200);
        call(HttpMethod.GET, "/api/admin/config", admin, null, 200);
    }
}
