package com.github.devnic0las.ledgerlens;

import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class TransactionTest   {


  @Test
  void shouldRejectTransactionUnbalanced() {

    var entries = List.of(
            new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.DEBIT, 10_000),
            new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.CREDIT, 9_000)
    );

    assertThrows(IllegalArgumentException.class, () ->
            new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", entries)
    );

  }

  @Test
  void shouldAcceptTransactionBalanced() {
    var entries = List.of(
            new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.DEBIT, 10_000),
            new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.CREDIT, 10_000)
    );

    assertDoesNotThrow(() -> new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", entries));
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

