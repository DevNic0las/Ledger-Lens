package com.github.devnic0las.ledgerlens.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unidade da regra de identidade da idempotência, isolada do banco e das threads.
 *
 * <p>A identidade é {@code description} + multiconjunto de {@code (conta, direção, valor)}.
 * {@code id} e {@code occurredAt} ficam de fora de propósito: o servidor gera os dois a cada
 * tentativa, então incluí-los faria todo retry legítimo ser tratado como conflito.
 */
class IdempotencyContentTest {

  private final UUID checking = UUID.randomUUID();
  private final UUID revenue = UUID.randomUUID();

  @Test
  void sameDescriptionAndSameEntriesMatch() {
    assertTrue(IdempotencyContent.matches(
            transaction("Pix aluguel", checking, revenue, 10_000),
            transaction("Pix aluguel", checking, revenue, 10_000)));
  }

  /** O buraco que esta regra fecha: mesma chave, mesma conta, mesmo valor, descrição diferente. */
  @Test
  void sameEntriesWithDifferentDescriptionDoNotMatch() {
    assertFalse(IdempotencyContent.matches(
            transaction("Pix aluguel", checking, revenue, 10_000),
            transaction("Pix mercado", checking, revenue, 10_000)));
  }

  @Test
  void sameDescriptionWithDifferentAmountDoesNotMatch() {
    assertFalse(IdempotencyContent.matches(
            transaction("Pix aluguel", checking, revenue, 10_000),
            transaction("Pix aluguel", checking, revenue, 99_999)));
  }

  @Test
  void sameDescriptionWithDifferentDirectionDoesNotMatch() {
    var a = new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", List.of(
            new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 10_000),
            new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, 10_000)));
    var b = new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", List.of(
            new Entry(UUID.randomUUID(), checking, Direction.CREDIT, 10_000),
            new Entry(UUID.randomUUID(), revenue, Direction.DEBIT, 10_000)));

    assertFalse(IdempotencyContent.matches(a, b));
  }

  /** Id e instante diferentes são o esperado num retry: não podem invalidar a correspondência. */
  @Test
  void differentIdAndOccurredAtStillMatch() {
    var a = new Transaction(UUID.randomUUID(), Instant.parse("2026-11-24T10:00:00Z"), "Pix", "chave-1",
            entries(checking, revenue, 10_000));
    var b = new Transaction(UUID.randomUUID(), Instant.parse("2026-11-25T23:59:59Z"), "Pix", "chave-1",
            entries(checking, revenue, 10_000));

    assertTrue(IdempotencyContent.matches(a, b));
  }

  /** Ordem dos lançamentos não importa: a comparação é de multiconjunto. */
  @Test
  void entryOrderDoesNotMatter() {
    var debit = new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 10_000);
    var credit = new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, 10_000);

    var a = new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", List.of(debit, credit));
    var b = new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", List.of(credit, debit));

    assertTrue(IdempotencyContent.matches(a, b));
  }

  /** Lançamentos repetidos contam: dois de 500 não são um de 1.000. */
  @Test
  void multiplicityIsCompared() {
    var a = new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", List.of(
            new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 500),
            new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 500),
            new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, 1_000)));
    var b = new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", List.of(
            new Entry(UUID.randomUUID(), checking, Direction.DEBIT, 1_000),
            new Entry(UUID.randomUUID(), revenue, Direction.CREDIT, 1_000)));

    assertFalse(IdempotencyContent.matches(a, b));
  }

  private Transaction transaction(String description, UUID from, UUID to, long cents) {
    return new Transaction(UUID.randomUUID(), Instant.now(), description, "chave-1",
            entries(from, to, cents));
  }

  private List<Entry> entries(UUID from, UUID to, long cents) {
    return List.of(
            new Entry(UUID.randomUUID(), from, Direction.DEBIT, cents),
            new Entry(UUID.randomUUID(), to, Direction.CREDIT, cents));
  }
}
