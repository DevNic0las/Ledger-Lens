package com.github.devnic0las.ledgerlens;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.ledger.domain.*;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LedgerTest {

  private Ledger ledger;
  private Account checking;
  private Account revenue;

  @BeforeEach
  void setUp() {
    ledger = new Ledger();
    checking = new Account(UUID.randomUUID(), "Conta Corrente", "BRL", AccountType.ASSET);
    revenue = new Account(UUID.randomUUID(), "Conta Receita", "BRL", AccountType.REVENUE);
  }

  private Transaction pix(String idempotencyKey, long cents) {
    return transaction("Pix", idempotencyKey, cents);
  }

  private Transaction transaction(String description, String idempotencyKey, long cents) {
    var entries = List.of(
            new Entry(UUID.randomUUID(), checking.id(), Direction.DEBIT, cents),
            new Entry(UUID.randomUUID(), revenue.id(), Direction.CREDIT, cents)
    );
    return new Transaction(UUID.randomUUID(), Instant.now(), description, idempotencyKey, entries);
  }

  @Test
  void balanceRespectsNormalSideOfEachAccountType() {
    var transaction = pix("chave-1", 10_000);

    ledger.register(checking);
    ledger.register(revenue);
    ledger.post(transaction);

    assertEquals(10_000, ledger.balanceOf(checking.id()));
    assertEquals(10_000, ledger.balanceOf(revenue.id()));

  }

  @Test
  void shouldRejectTransactionWithUnknownAccount() {
    var transaction = pix("chave-1", 10_000);

    assertThrows(IllegalArgumentException.class, () -> ledger.post(transaction));
  }

  @Test
  void failedPostDoesNotBurnIdempotencyKey() {
    var transaction = pix("chave-1", 10_000);

    ledger.register(revenue);

    assertThrows(IllegalArgumentException.class, () -> ledger.post(transaction));

    ledger.register(checking);
    ledger.post(transaction);

    assertEquals(10_000, ledger.balanceOf(checking.id()));
  }


  @Test
  void postingSameKeyTwiceReturnsDuplicateAndKeepsBalance() {

    ledger.register(checking);
    ledger.register(revenue);
    var transaction = pix("chave-1", 10_000);


    assertEquals(PostResult.POSTED, ledger.post(transaction));

    assertEquals(PostResult.DUPLICATE, ledger.post(transaction));

    assertEquals(10_000, ledger.balanceOf(checking.id()));
    assertEquals(10_000, ledger.balanceOf(revenue.id()));
  }

  @Test
  void sameKeyWithDifferentContentThrows() {
    ledger.register(checking);
    ledger.register(revenue);
    ledger.post(pix("chave-1", 10_000));
    assertThrows(IdempotencyConflictException.class, () -> ledger.post(pix("chave-1", 99_999)));
  }

  /** Mesma chave, mesmos lançamentos, descrição diferente: conflito, não `DUPLICATE` silencioso. */
  @Test
  void sameKeyWithDifferentDescriptionThrows() {
    ledger.register(checking);
    ledger.register(revenue);
    ledger.post(transaction("Pix aluguel", "chave-1", 10_000));

    assertThrows(IdempotencyConflictException.class,
            () -> ledger.post(transaction("Pix mercado", "chave-1", 10_000)));
  }

  /**
   * O mesmo contrato de concorrência que o banco entrega, aplicado ao Ledger em memória:
   * várias threads postando a MESMA chave ao mesmo tempo devem produzir exatamente um
   * POSTED, o restante DUPLICATE, e o saldo deve refletir UMA única vez o lançamento.
   */
  @Test
  void concurrentPostsOfSameKeyProduceExactlyOnePosted() throws Exception {
    ledger.register(checking);
    ledger.register(revenue);
    var transaction = pix("chave-concorrente", 10_000);

    var rounds = 50;
    var pool = Executors.newFixedThreadPool(4);
    try {
      for (int round = 0; round < rounds; round++) {
        var fresh = new Ledger();
        fresh.register(checking);
        fresh.register(revenue);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<PostResult>>();

        for (int i = 0; i < 4; i++) {
          futures.add(pool.submit(() -> {
            start.await();
            return fresh.post(transaction);
          }));
        }
        start.countDown();

        long posted = 0;
        long duplicates = 0;
        for (var future : futures) {
          var outcome = future.get(30, TimeUnit.SECONDS);
          if (outcome == PostResult.POSTED) posted++;
          if (outcome == PostResult.DUPLICATE) duplicates++;
        }

        assertEquals(1, posted, "rodada " + round + ": exatamente um post deve ser POSTED");
        assertEquals(3, duplicates, "rodada " + round + ": os demais devem ser DUPLICATE");
        assertEquals(10_000, fresh.balanceOf(checking.id()),
                "rodada " + round + ": o saldo não pode contar a mesma chave duas vezes");
        assertEquals(10_000, fresh.balanceOf(revenue.id()), "rodada " + round);
      }
    } finally {
      pool.shutdownNow();
    }
  }

}
