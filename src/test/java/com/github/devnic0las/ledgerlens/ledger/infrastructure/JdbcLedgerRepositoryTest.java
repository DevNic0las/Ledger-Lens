package com.github.devnic0las.ledgerlens.ledger.infrastructure;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcLedgerRepositoryTest {

  @Autowired
  JdbcClient jdbc;

  @Autowired
  LedgerRepository repository;

  private UUID checking;
  private UUID revenue;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE TABLE entry, transaction, account").update();
    checking = anAccount("Conta Corrente", "ASSET");
    revenue = anAccount("Conta Receita", "REVENUE");
  }

  @Test
  void savesAndReadsTransactionWithItsEntries() {
    var transaction = pix("chave-1", 10_000);

    assertEquals(PostResult.POSTED, repository.save(transaction));

    var found = repository.findById(transaction.id());
    assertTrue(found.isPresent());
    assertEquals(transaction.id(), found.get().id());
    assertEquals(transaction.description(), found.get().description());
    assertEquals(transaction.idempotencyKey(), found.get().idempotencyKey());
    assertEquals(transaction.occurredAt(), found.get().occurredAt());
    assertEquals(2, found.get().entries().size());
    assertEquals(
            transaction.entries().stream().map(Entry::amountCents).toList(),
            found.get().entries().stream().map(Entry::amountCents).toList());
  }

  @Test
  void findsTransactionByIdempotencyKey() {
    var transaction = pix("chave-1", 10_000);
    repository.save(transaction);

    var found = repository.findByIdempotencyKey("chave-1");

    assertTrue(found.isPresent());
    assertEquals(transaction.id(), found.get().id());
  }

  @Test
  void answersAbsentForUnknownIdAndKey() {
    assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    assertTrue(repository.findByIdempotencyKey("nunca-usada").isEmpty());
  }

  @Test
  void sameIdempotencyKeyIsReportedAsDuplicateAndStoredOnce() {
    var transaction = pix("chave-1", 10_000);
    repository.save(transaction);

    var retry = pix("chave-1", 10_000);

    assertEquals(PostResult.DUPLICATE, repository.save(retry));
    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));

    var stored = repository.findByIdempotencyKey("chave-1").orElseThrow();
    assertEquals(transaction.id(), stored.id());
  }

  @Test
  void failingEntrySavesNothing() {
    var missingAccount = new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.CREDIT, 200);
    var unpostable = new Transaction(
            UUID.randomUUID(),
            nowMicros(),
            "Pix quebrado",
            "chave-quebrada",
            List.of(
                    new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 500),
                    new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, 300),
                    missingAccount));

    assertThrows(RuntimeException.class, () -> repository.save(unpostable));

    assertEquals(0, rowCount("transaction"));
    assertEquals(0, rowCount("entry"));
    assertTrue(repository.findByIdempotencyKey("chave-quebrada").isEmpty());
  }

  @Test
  void failedSaveDoesNotBurnIdempotencyKey() {
    var missingAccount = new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.CREDIT, 200);
    var unpostable = new Transaction(
            UUID.randomUUID(),
            nowMicros(),
            "Pix quebrado",
            "chave-1",
            List.of(
                    new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 500),
                    new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, 300),
                    missingAccount));

    assertThrows(RuntimeException.class, () -> repository.save(unpostable));

    assertEquals(PostResult.POSTED, repository.save(pix("chave-1", 10_000)));
  }

  private Transaction pix(String idempotencyKey, long cents) {
    return new Transaction(
            UUID.randomUUID(),
            nowMicros(),
            "Pix",
            idempotencyKey,
            List.of(
                    new Entry(UUID.randomUUID(), checking, Direction.DEBIT, cents),
                    new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, cents)));
  }

  /** TIMESTAMPTZ guarda microssegundos; gerar o instante já nessa precisão deixa o round-trip exato. */
  private static Instant nowMicros() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private UUID anAccount(String name, String type) {
    var id = UUID.randomUUID();
    jdbc.sql("INSERT INTO account (id, name, type, currency) VALUES (:id, :name, :type, :currency)")
            .param("id", id)
            .param("name", name)
            .param("type", type)
            .param("currency", "BRL")
            .update();
    return id;
  }

  private long rowCount(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
