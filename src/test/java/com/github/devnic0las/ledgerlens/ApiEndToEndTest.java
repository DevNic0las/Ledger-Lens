package com.github.devnic0las.ledgerlens;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ponta a ponta pela borda HTTP, com servidor real e Postgres real: as regras que só existem
 * no domínio (balanceamento, idempotência) e as que só existem na borda (status HTTP,
 * {@code ProblemDetail}) só são provadas juntas aqui.
 *
 * <p>Usa o {@link HttpClient} do JDK em vez do cliente de teste do Spring: o alvo é o
 * contrato HTTP, e um cliente neutro não deixa dúvida sobre quem montou a requisição.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApiEndToEndTest {

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort
  int port;

  @Autowired
  JdbcClient jdbc;

  @Test
  void postsOnceThenAnswersTheOriginalOnRetryAndServesTheBalance() throws Exception {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    var checking = anAccount("Conta Corrente", "ASSET");
    var revenue = anAccount("Conta Receita", "REVENUE");
    var body = body("Pix e2e", checking, revenue, 10_000);

    var first = post(body, "chave-e2e");
    var retry = post(body, "chave-e2e");

    assertEquals(201, first.statusCode());
    assertEquals(200, retry.statusCode());
    assertEquals(idOf(first.body()), idOf(retry.body()),
            "o retry devolve a transação original, não uma nova");

    var balance = get("/accounts/" + checking + "/balance");
    assertEquals(200, balance.statusCode());
    assertTrue(balance.body().contains("\"balanceCents\":10000"), balance.body());
  }

  /** Mesma chave com descrição diferente: 409, não 200 silencioso. */
  @Test
  void sameKeyWithDifferentDescriptionIs409() throws Exception {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    var checking = anAccount("Conta Corrente", "ASSET");
    var revenue = anAccount("Conta Receita", "REVENUE");

    assertEquals(201, post(body("Pix e2e", checking, revenue, 10_000), "chave-e2e").statusCode());

    assertEquals(409, post(body("Pix outra", checking, revenue, 10_000), "chave-e2e").statusCode());
  }

  @Test
  void missingAccountsAre422ListingTheIds() throws Exception {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    var unknown = UUID.randomUUID();
    var revenue = anAccount("Conta Receita", "REVENUE");

    var response = post(body("Pix e2e", unknown, revenue, 10_000), "chave-e2e");

    assertEquals(422, response.statusCode());
    assertTrue(response.body().contains(unknown.toString()), response.body());
  }

  @Test
  void unbalancedIs422AndMissingHeaderIs400() throws Exception {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    var checking = anAccount("Conta Corrente", "ASSET");
    var revenue = anAccount("Conta Receita", "REVENUE");

    var unbalanced = """
            {"description":"Torta","entries":[
              {"accountId":"%s","direction":"DEBIT","amountCents":500},
              {"accountId":"%s","direction":"CREDIT","amountCents":400}]}
            """.formatted(checking, revenue);

    assertEquals(422, post(unbalanced, "chave-e2e").statusCode());

    var noHeader = http.send(HttpRequest.newBuilder(uri("/transactions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body("Pix", checking, revenue, 100)))
            .build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(400, noHeader.statusCode());
  }

  @Test
  void unknownAccountBalanceIs404() throws Exception {
    var response = get("/accounts/" + UUID.randomUUID() + "/balance");

    assertEquals(404, response.statusCode());
  }

  /**
   * O {@code Location} do {@code 201} tem que ser seguível: criar a conta, ler a URL anunciada
   * e receber a mesma conta de volta. Sem o GET, um {@code Location} apontando para o lugar
   * errado (o saldo, por exemplo) passaria no teste.
   */
  @Test
  void createsAccountThroughTheApiAndUsesTheGeneratedId() throws Exception {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();

    var created = http.send(HttpRequest.newBuilder(uri("/accounts"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\"Caixa\",\"currency\":\"BRL\",\"type\":\"ASSET\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());

    assertEquals(201, created.statusCode());

    var location = created.headers().firstValue("Location").orElseThrow();
    assertEquals("/accounts/" + idOf(created.body()), location);

    var fetched = get(location);
    assertEquals(200, fetched.statusCode(), fetched.body());
    assertTrue(fetched.body().contains("\"id\":\"" + idOf(created.body()) + "\""), fetched.body());
    assertTrue(fetched.body().contains("\"name\":\"Caixa\""), fetched.body());
    assertTrue(fetched.body().contains("\"currency\":\"BRL\""), fetched.body());
    assertTrue(fetched.body().contains("\"type\":\"ASSET\""), fetched.body());
  }

  /** O conflito de `(name, currency)` vem da constraint, não de pré-consulta. */
  @Test
  void duplicateAccountIs409() throws Exception {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    var create = HttpRequest.newBuilder(uri("/accounts"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\"Caixa\",\"currency\":\"BRL\",\"type\":\"ASSET\"}"))
            .build();

    http.send(create, HttpResponse.BodyHandlers.ofString());
    var duplicate = http.send(create, HttpResponse.BodyHandlers.ofString());

    assertEquals(409, duplicate.statusCode());
  }

  private HttpResponse<String> post(String body, String idempotencyKey) throws Exception {
    return http.send(HttpRequest.newBuilder(uri("/transactions"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static String body(String description, UUID debit, UUID credit, long cents) {
    return """
            {"description":"%s","entries":[
              {"accountId":"%s","direction":"DEBIT","amountCents":%d},
              {"accountId":"%s","direction":"CREDIT","amountCents":%d}]}
            """.formatted(description, debit, cents, credit, cents);
  }

  /** Extrai o campo {@code id} da transação sem trazer uma árvore JSON só para isso. */
  private static String idOf(String json) {
    var start = json.indexOf("\"id\":\"") + 6;
    return json.substring(start, json.indexOf('"', start));
  }

  private UUID anAccount(String name, String type) {
    var id = UUID.randomUUID();
    jdbc.sql("INSERT INTO account (id, name, type, currency) VALUES (:id, :name, :type, 'BRL')")
            .param("id", id)
            .param("name", name)
            .param("type", type)
            .update();
    return id;
  }
}
