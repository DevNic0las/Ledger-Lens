package com.github.devnic0las.ledgerlens.ledger.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class IdempotencyContent {

  private IdempotencyContent() {}

  public static boolean matches(Transaction a, Transaction b) {
    return shapesOf(a).equals(shapesOf(b));
  }

  private static Map<EntryShape, Long> shapesOf(Transaction transaction) {
    Map<EntryShape, Long> shapes = new HashMap<>();

    for (Entry entry : transaction.entries()) {
      var shape = new EntryShape(entry.accountId(), entry.direction(), entry.amountCents());
      shapes.merge(shape, 1L, Long::sum);
    }

    return shapes;
  }

  private record EntryShape(UUID accountId, Direction direction, long amountCents) {}
}
