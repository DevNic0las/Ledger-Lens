package com.github.devnic0las.ledgerlens;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prova, contra um Postgres real, que o schema da V1 recusa o que o domínio recusa.
 * JDBC puro de propósito: o objetivo é medir o banco, não o mapeamento objeto-relacional.
 */
@Testcontainers
class LedgerSchemaTest {

  private static final String CHECK_VIOLATION = "23514";
  private static final String FOREIGN_KEY_VIOLATION = "23503";
  private static final String UNIQUE_VIOLATION = "23505";

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
  }

  @BeforeEach
  void clean() throws SQLException {
    execute("TRUNCATE TABLE entry, transaction, account");
  }

  @Test
  void validAccountTransactionAndEntriesArePersisted() throws SQLException {
    var checking = anAccount("Conta Corrente", "ASSET");
    var revenue = anAccount("Conta Receita", "REVENUE");
    var transaction = aTransaction("chave-1");

    anEntry(transaction, checking, "DEBIT", 10_000);
    anEntry(transaction, revenue, "CREDIT", 10_000);

    assertEquals(2, count("SELECT count(*) FROM entry"));
  }

  @Test
  void accountTypeMustBeKnown() {
    assertEquals(CHECK_VIOLATION, sqlStateOf(
            "INSERT INTO account (id, name, type, currency) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), "Conta", "BITCOIN", "BRL"));
  }

  @Test
  void entryAmountMustBePositive() throws SQLException {
    var checking = anAccount("Conta Corrente", "ASSET");
    var revenue = anAccount("Conta Receita", "REVENUE");
    var transaction = aTransaction("chave-1");

    assertEquals(CHECK_VIOLATION, sqlStateOf(
            "INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), transaction, checking, "DEBIT", 0L));
    assertEquals(CHECK_VIOLATION, sqlStateOf(
            "INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), transaction, revenue, "CREDIT", 0L));
  }

  @Test
  void entryAmountMustNotBeNegative() throws SQLException {
    var checking = anAccount("Conta Corrente", "ASSET");
    var transaction = aTransaction("chave-1");

    assertEquals(CHECK_VIOLATION, sqlStateOf(
            "INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), transaction, checking, "DEBIT", -1L));
  }

  @Test
  void directionMustBeDebitOrCredit() throws SQLException {
    var checking = anAccount("Conta Corrente", "ASSET");
    var transaction = aTransaction("chave-1");

    assertEquals(CHECK_VIOLATION, sqlStateOf(
            "INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), transaction, checking, "SIDEWAYS", 100L));
  }

  @Test
  void entryMustReferenceExistingAccount() throws SQLException {
    var checking = anAccount("Conta Corrente", "ASSET");
    var transaction = aTransaction("chave-1");

    assertEquals(FOREIGN_KEY_VIOLATION, sqlStateOf(
            "INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), transaction, UUID.randomUUID(), "DEBIT", 100L));
  }

  @Test
  void entryMustReferenceExistingTransaction() throws SQLException {
    var checking = anAccount("Conta Corrente", "ASSET");

    assertEquals(FOREIGN_KEY_VIOLATION, sqlStateOf(
            "INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), UUID.randomUUID(), checking, "DEBIT", 100L));
  }

  @Test
  void idempotencyKeyMustBeUnique() throws SQLException {
    aTransaction("chave-1");

    assertEquals(UNIQUE_VIOLATION, sqlStateOf(
            "INSERT INTO transaction (id, occurred_at, description, idempotency_key) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), Timestamp.from(Instant.now()), "Outro Pix", "chave-1"));
  }

  private static UUID anAccount(String name, String type) throws SQLException {
    var id = UUID.randomUUID();
    execute("INSERT INTO account (id, name, type, currency) VALUES (?, ?, ?, ?)", id, name, type, "BRL");
    return id;
  }

  private static UUID aTransaction(String idempotencyKey) throws SQLException {
    var id = UUID.randomUUID();
    execute("INSERT INTO transaction (id, occurred_at, description, idempotency_key) VALUES (?, ?, ?, ?)",
            id, Timestamp.from(Instant.now()), "Pix", idempotencyKey);
    return id;
  }

  private static void anEntry(UUID transactionId, UUID accountId, String direction, long amountCents)
          throws SQLException {
    execute("INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), transactionId, accountId, direction, amountCents);
  }

  private static void execute(String sql, Object... args) throws SQLException {
    try (var connection = connect(); var statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      statement.executeUpdate();
    }
  }

  private static String sqlStateOf(String sql, Object... args) {
    var exception = assertThrows(SQLException.class, () -> execute(sql, args));
    return exception.getSQLState();
  }

  private static long count(String sql) throws SQLException {
    try (var connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void bind(PreparedStatement statement, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      statement.setObject(i + 1, args[i]);
    }
  }
}
