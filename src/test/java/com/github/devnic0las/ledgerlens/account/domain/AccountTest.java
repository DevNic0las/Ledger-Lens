package com.github.devnic0las.ledgerlens.account.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Invariantes de {@link Account} no construtor: nenhuma conta inválida pode ser construída,
 * venha ela de teste, de um controller ou de um adaptador futuro.
 */
class AccountTest {

  @Test
  void acceptsAWellFormedAccount() {
    assertDoesNotThrow(() ->
            new Account(UUID.randomUUID(), "Conta Corrente", "BRL", AccountType.ASSET));
  }

  @Test
  void rejectsNullId() {
    assertThrows(NullPointerException.class, () ->
            new Account(null, "Conta Corrente", "BRL", AccountType.ASSET));
  }

  @Test
  void rejectsNullName() {
    assertThrows(NullPointerException.class, () ->
            new Account(UUID.randomUUID(), null, "BRL", AccountType.ASSET));
  }

  @Test
  void rejectsBlankName() {
    assertThrows(IllegalArgumentException.class, () ->
            new Account(UUID.randomUUID(), "   ", "BRL", AccountType.ASSET));
  }

  @Test
  void rejectsNullCurrency() {
    assertThrows(NullPointerException.class, () ->
            new Account(UUID.randomUUID(), "Conta Corrente", null, AccountType.ASSET));
  }

  @Test
  void rejectsCurrencyThatIsNotThreeUpperCaseLetters() {
    for (String invalid : new String[]{"brl", "Brl", "BR", "BRLL", "B R", "", "R$1"}) {
      assertThrows(IllegalArgumentException.class, () ->
              new Account(UUID.randomUUID(), "Conta Corrente", invalid, AccountType.ASSET),
              "deveria recusar currency: " + invalid);
    }
  }

  @Test
  void rejectsNullType() {
    assertThrows(NullPointerException.class, () ->
            new Account(UUID.randomUUID(), "Conta Corrente", "BRL", null));
  }
}
