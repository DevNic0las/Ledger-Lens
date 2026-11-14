package com.github.devnic0las.ledgerlens;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.Ledger;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    var entries = List.of(
            new Entry(UUID.randomUUID(), checking.id(), Direction.DEBIT, cents),
            new Entry(UUID.randomUUID(), revenue.id(), Direction.CREDIT, cents)
    );
    return new Transaction(UUID.randomUUID(), Instant.now(), "Pix", idempotencyKey, entries);
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
}
