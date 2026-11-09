package br.com.quickpass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Base dos testes de API: contexto novo por teste (estado em memória) e helpers de chamada HTTP. */
@SpringBootTest(properties = {"quickpass.scheduler.intervalo-ms=3600000", "quickpass.uploads.dir=target/uploads-teste"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
abstract class ApiTestBase {
    static final String[] CPFS = {"52998224725", "11144477735", "39053344705", "86288366757", "01234567890"};
    static int cpfSeq = 0;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    static LocalDate hoje() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    JsonNode call(HttpMethod m, String url, String token, Object body, int esperado) throws Exception {
        MockHttpServletRequestBuilder b = request(m, url);
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        var res = mvc.perform(b).andReturn().getResponse();
        String corpo = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(esperado, res.getStatus(), m + " " + url + " → " + corpo);
        return corpo.isBlank() ? null : om.readTree(corpo);
    }

    /** Imagem PNG mínima (assinatura válida) para os uploads. */
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0};

    static MockMultipartFile imagem(String parte) {
        return new MockMultipartFile(parte, "foto.png", "image/png", PNG);
    }

    JsonNode json(Object o) throws Exception {
        return om.valueToTree(o);
    }

    /** POST multipart: {@code dados} vira a parte JSON "dados"; {@code arquivos} são as demais partes. */
    JsonNode multipart(String url, String token, Object dados, int esperado, MockMultipartFile... arquivos) throws Exception {
        var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(url);
        if (token != null) b.header("Authorization", "Bearer " + token);
        for (var f : arquivos) b.file(f);
        if (dados != null) b.file(new MockMultipartFile("dados", "", "application/json", om.writeValueAsBytes(dados)));
        var res = mvc.perform(b).andReturn().getResponse();
        String corpo = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(esperado, res.getStatus(), "POST " + url + " → " + corpo);
        return corpo.isBlank() ? null : om.readTree(corpo);
    }

    MockMultipartFile[] imagens(String parte, int n) {
        MockMultipartFile[] a = new MockMultipartFile[n];
        for (int i = 0; i < n; i++) a[i] = imagem(parte);
        return a;
    }

    JsonNode enviarKyc(String item, String token, int esperado) throws Exception {
        return multipart("/api/me/kyc/" + item, token, null, esperado, imagem("arquivo"));
    }

    JsonNode get(String url, String token) throws Exception {
        return call(HttpMethod.GET, url, token, null, 200);
    }

    JsonNode post(String url, String token, Object body) throws Exception {
        return call(HttpMethod.POST, url, token, body, 200);
    }

    String login(String email, String senha) throws Exception {
        return post("/api/auth/login", null, Map.of("email", email, "senha", senha)).get("token").asText();
    }

    String admin() throws Exception {
        return login("admin@quickpass.com", "admin123");
    }

    /** Cadastra um usuário novo e devolve o token; {@code kyc} envia os 4 itens. */
    String novoUsuario(String email, List<String> papeis, boolean kyc, Map<String, Object> extra) throws Exception {
        Map<String, Object> corpo = new java.util.HashMap<>(Map.of("nome", "Teste " + email, "email", email, "senha", "segredo1",
                "cpf", CPFS[cpfSeq++ % CPFS.length], "papeis", papeis, "cnhAnos", 5));
        corpo.putAll(extra);
        String token = call(HttpMethod.POST, "/api/auth/cadastro", null, corpo, 201).get("token").asText();
        if (kyc) for (String i : List.of("DOC", "CNH", "SELFIE", "BANCO")) enviarKyc(i, token, 200);
        return token;
    }

    void avancarHoras(long horas) throws Exception {
        post("/api/admin/relogio/avancar", admin(), Map.of("horas", horas));
    }

    void jobs() throws Exception {
        post("/api/admin/jobs/executar", admin(), null);
    }

    /** Locatário solicita (veículo "1" do p1), proprietário aprova e locatário paga → CONFIRMADA. Devolve o id da reserva. */
    String reservaConfirmada(String locatario, LocalDate inicio, LocalDate fim) throws Exception {
        JsonNode r = call(HttpMethod.POST, "/api/reservas", locatario,
                Map.of("veiculoId", "1", "inicio", inicio.toString(), "fim", fim.toString(), "protecao", "BASICA", "finalidade", "teste"), 201);
        String id = r.get("id").asText();
        post("/api/reservas/" + id + "/aprovar", login("p1@example.com", "senha123"), null);
        JsonNode paga = post("/api/reservas/" + id + "/pagar", locatario, Map.of("metodo", "cartao", "numeroCartao", "4111111111111111"));
        assertEquals("CONFIRMADA", paga.get("status").asText());
        return id;
    }

    /** Fotos + aceites das duas partes + conclusão da vistoria. */
    JsonNode vistoria(String id, String tipo, String locatario, String proprietario, boolean avaria) throws Exception {
        for (String f : List.of("FRENTE", "TRASEIRA", "LATERAL_ESQUERDA", "LATERAL_DIREITA", "PAINEL", "ESTOFAMENTO", "COMBUSTIVEL")) {
            multipart("/api/reservas/" + id + "/vistorias/" + tipo + "/fotos/" + f, locatario, null, 200, imagem("arquivo"));
        }
        post("/api/reservas/" + id + "/vistorias/" + tipo + "/aceite", locatario, null);
        post("/api/reservas/" + id + "/vistorias/" + tipo + "/aceite", proprietario, null);
        return post("/api/reservas/" + id + "/vistorias/" + tipo + "/concluir", locatario, Map.of("avaria", avaria));
    }
}
