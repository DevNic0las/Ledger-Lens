package com.github.devnic0las.ledgerlens.ledger.infrastructure;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcLedgerRepositoryTest {

  @Autowired
  JdbcClient jdbc;

  @Autowired
  LedgerRepository repository;

  @Autowired
  PlatformTransactionManager transactionManager;

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

  @Test
  void sameIdempotencyKeyWithDifferentContentThrowsConflictAndKeepsOriginalTransaction() {
    var original = pix("chave-1", 10_000);
    repository.save(original);

    var conflict = pix("chave-1", 99_999);

    assertThrows(IdempotencyConflictException.class, () -> repository.save(conflict));
    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));

    var stored = repository.findByIdempotencyKey("chave-1").orElseThrow();
    assertEquals(original.id(), stored.id());
    assertEquals(
            original.entries().stream().map(Entry::amountCents).toList(),
            stored.entries().stream().map(Entry::amountCents).toList());
  }

  /**
   * Descrição faz parte da identidade: mesma chave, mesmos lançamentos, descrição diferente é
   * conflito — devolver a original em silêncio esconderia um bug do cliente.
   */
  @Test
  void sameIdempotencyKeyWithDifferentDescriptionThrowsConflict() {
    repository.save(transaction("Pix", "chave-1", 10_000));

    assertThrows(IdempotencyConflictException.class,
            () -> repository.save(transaction("Pix aluguel", "chave-1", 10_000)));

    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));
  }

  /** O que continua fora da identidade: `id` e `occurredAt`, gerados a cada tentativa. */
  @Test
  void sameKeySameContentWithNewInstantIsStillDuplicate() {
    repository.save(transaction("Pix", "chave-1", 10_000));

    var retry = new Transaction(UUID.randomUUID(), nowMicros().plusSeconds(60), "Pix", "chave-1",
            List.of(
                    new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 10_000),
                    new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, 10_000)));

    assertEquals(PostResult.DUPLICATE, repository.save(retry));
    assertEquals(1, rowCount("transaction"));
  }

  @Test
  void primaryKeyCollisionStillPropagatesDuplicateKeyException() {
    var id = UUID.randomUUID();
    repository.save(pix(id, "chave-1", 10_000));

    assertThrows(DuplicateKeyException.class, () -> repository.save(pix(id, "chave-2", 10_000)));
  }

  /**
   * O caso de uso real (Etapa 4) chama {@code save} de dentro de uma transação própria
   * ({@code @Transactional}). Repetir a mesma chave com o mesmo conteúdo precisa devolver
   * {@code DUPLICATE} sem exceção, e a transação do chamador precisa continuar utilizável.
   *
   * <p>Com check-then-act via {@code catch (DuplicateKeyException)} isso falha: o INSERT
   * viola a UNIQUE, o Postgres aborta a transação inteira (SQLState 25P02) e a consulta de
   * recuperação não pode mais ser executada. {@code ON CONFLICT DO NOTHING} não gera erro.
   */
  @Test
  void retryWithSameKeyInsideCallerTransactionReturnsDuplicate() {
    repository.save(pix("chave-1", 10_000));
    var transactionTemplate = new TransactionTemplate(transactionManager);

    var result = transactionTemplate.execute(status -> {
      var outcome = repository.save(pix("chave-1", 10_000));
      // A transação do chamador continua utilizável depois do retry.
      jdbc.sql("SELECT count(*) FROM transaction").query(Long.class).single();
      return outcome;
    });

    assertEquals(PostResult.DUPLICATE, result);
    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));
  }

  @Test
  void conflictInsideCallerTransactionCommitsNothing() {
    repository.save(pix("chave-1", 10_000));
    var transactionTemplate = new TransactionTemplate(transactionManager);

    assertThrows(IdempotencyConflictException.class, () ->
            transactionTemplate.executeWithoutResult(status -> {
              jdbc.sql("INSERT INTO account (id, name, type, currency) VALUES (:id, 'Fantasma', 'ASSET', 'BRL')")
                      .param("id", UUID.randomUUID())
                      .update();
              repository.save(pix("chave-1", 99_999));
            }));

    assertEquals(2, rowCount("account"), "o efeito colateral do caso de uso não pode ser commitado");
    assertEquals(1, rowCount("transaction"));
  }

  @Test
  void concurrentSameKeyYieldsExactlyOnePostedRepeatedly() throws Exception {
    var pool = Executors.newFixedThreadPool(4);
    try {
      for (int round = 0; round < 20; round++) {
        jdbc.sql("TRUNCATE TABLE entry, transaction").update();
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<PostResult>>();

        for (int i = 0; i < 4; i++) {
          results.add(pool.submit(() -> {
            start.await();
            return repository.save(pix("chave-concorrente", 10_000));
          }));
        }
        start.countDown();

        long posted = 0;
        long duplicates = 0;
        for (var future : results) {
          var outcome = future.get(30, TimeUnit.SECONDS);
          if (outcome == PostResult.POSTED) posted++;
          if (outcome == PostResult.DUPLICATE) duplicates++;
        }

        assertEquals(1, posted, "rodada " + round + ": exatamente uma requisição grava");
        assertEquals(3, duplicates, "rodada " + round + ": as demais recebem DUPLICATE");
        assertEquals(1, rowCount("transaction"), "rodada " + round);
        assertEquals(2, rowCount("entry"), "rodada " + round);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private Transaction pix(String idempotencyKey, long cents) {
    return pix(UUID.randomUUID(), idempotencyKey, cents);
  }

  private Transaction pix(UUID id, String idempotencyKey, long cents) {
    return transaction("Pix", id, idempotencyKey, cents);
  }

  private Transaction transaction(String description, String idempotencyKey, long cents) {
    return transaction(description, UUID.randomUUID(), idempotencyKey, cents);
  }

  private Transaction transaction(String description, UUID id, String idempotencyKey, long cents) {
    return new Transaction(
            id,
            nowMicros(),
            description,
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
