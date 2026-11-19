package com.github.devnic0las.ledgerlens.ledger.infrastructure;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
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
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
public class JdbcLedgerRepositoryConcurrencyTest {

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
  void concurrentSavesWithSameKeyYieldExactlyOnePostedAndOneDuplicate() throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<PostResult> t1Result = new AtomicReference<>();
    AtomicReference<PostResult> t2Result = new AtomicReference<>();
    AtomicReference<Throwable> t1Ex = new AtomicReference<>();
    AtomicReference<Throwable> t2Ex = new AtomicReference<>();

    Thread t1 = new Thread(() -> race(ready, start, t1Result, t1Ex));
    Thread t2 = new Thread(() -> race(ready, start, t2Result, t2Ex));

    t1.start();
    t2.start();
    ready.await();
    start.countDown();
    t1.join();
    t2.join();

    assertNull(t1Ex.get(), "Thread 1 falhou: " + t1Ex.get());
    assertNull(t2Ex.get(), "Thread 2 falhou: " + t2Ex.get());

    // A regra: exatamente uma grava e a outra recebe DUPLICATE, nunca duas POSTED nem stava.
    var results = List.of(t1Result.get(), t2Result.get());
    assertEquals(1, results.stream().filter(PostResult.POSTED::equals).count(),
            "exatamente uma thread deve receber POSTED, mas foi: " + results);
    assertEquals(1, results.stream().filter(PostResult.DUPLICATE::equals).count(),
            "exatamente uma thread deve receber DUPLICATE, mas foi: " + results);

    assertEquals(1, rowCount("transaction"));
    assertEquals(2, rowCount("entry"));
    assertNotNull(repository.findByIdempotencyKey("chave-1"));
  }

  private void race(CountDownLatch ready, CountDownLatch start,
                    AtomicReference<PostResult> result, AtomicReference<Throwable> error) {
    try {
      ready.countDown();
      start.await();
      result.set(repository.save(pix("chave-1", 10_000)));
    } catch (Throwable e) {
      error.set(e);
    }
  }


  @Test
  void sameKeyWithDifferentContentThrows() {

    repository.save(pix("chave-1", 10_000));

    assertThrows(
            IdempotencyConflictException.class,
            () -> repository.save(pix("chave-1", 99_999))
    );
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

  private static Instant nowMicros() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
  private long rowCount(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
