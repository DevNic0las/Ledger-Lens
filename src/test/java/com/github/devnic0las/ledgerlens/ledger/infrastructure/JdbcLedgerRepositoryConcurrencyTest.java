package com.github.devnic0las.ledgerlens.ledger.infrastructure;

import com.github.devnic0las.ledgerlens.TestcontainersConfiguration;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
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
  void idempotentTransactionsAreNotSavedTwice() throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> t1Ex = new AtomicReference<>();
    AtomicReference<Throwable> t2Ex = new AtomicReference<>();
    Thread t1 = new Thread(()-> {
      try {
        ready.countDown();
        start.await();
        repository.save(pix("chave-1", 10_000));
      } catch (Throwable e) {
        t1Ex.set(e);
      }



    });
    Thread t2 = new Thread(() -> {
      try {
        ready.countDown();
        start.await();
        repository.save(pix("chave-1", 10_000));
      } catch (Throwable e) {
        t2Ex.set(e);
      }
    });
    t1.start();
    t2.start();
    ready.await();
    start.countDown();
    t1.join();
    t2.join();

    assertNull(t1Ex.get(), "Thread 1 falhou");
    assertNull(t2Ex.get(), "Thread 2 falhou");


    assertEquals(1, rowCount("transaction"));
    assertNotNull(repository.findByIdempotencyKey("chave-1"));

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
