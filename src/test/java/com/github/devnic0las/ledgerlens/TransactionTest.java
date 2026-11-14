package com.github.devnic0las.ledgerlens;

import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class TransactionTest   {

  private UUID checkingAccountId;
  private UUID revenueAccountId;

  @BeforeEach
  void setUp() {
    checkingAccountId = UUID.randomUUID();
    revenueAccountId = UUID.randomUUID();
  }

  private Transaction pix(String idempotencyKey, long debitCents, long creditCents) {
    var entries = List.of(
            new Entry(UUID.randomUUID(), checkingAccountId, Direction.DEBIT, debitCents),
            new Entry(UUID.randomUUID(), revenueAccountId, Direction.CREDIT, creditCents)
    );
    return new Transaction(UUID.randomUUID(), Instant.now(), "Pix", idempotencyKey, entries);
  }

  @Test
  void shouldRejectTransactionUnbalanced() {
    assertThrows(IllegalArgumentException.class, () ->
            pix("chave-1", 10_000, 9_000)
    );

  }

  @Test
  void shouldAcceptTransactionBalanced() {
    assertDoesNotThrow(() -> pix("chave-1", 10_000, 10_000));
  }

  @Test
  void shouldRejectEmptyTransaction() {
    var entries = List.<Entry>of();

    assertThrows(IllegalArgumentException.class, () ->
            new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", entries)
    );
  }

  @Test
  void shouldRejectZeroAmount() {
    assertThrows(IllegalArgumentException.class, () ->
            new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.DEBIT, 0)
    );
  }

}
