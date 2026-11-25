package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forma do pedido de criação de conta. O serviço é uma casca fina sobre o repositório; o que
 * vale testar sem banco é a validação de forma e o id gerado.
 */
class CreateAccountCommandTest {

  @Test
  void generatesIdOnTheServer() {
    var first = new CreateAccountCommand("Caixa", "BRL", AccountType.ASSET);
    var second = new CreateAccountCommand("Caixa", "BRL", AccountType.ASSET);

    assertNotNull(first.toAccount().id());
    assertNotEquals(first.toAccount().id(), second.toAccount().id(),
            "cada pedido gera seu próprio id");
  }

  @Test
  void keepsNameCurrencyAndType() {
    var account = new CreateAccountCommand("Caixa", "BRL", AccountType.LIABILITY).toAccount();

    assertEquals("Caixa", account.name());
    assertEquals("BRL", account.currency());
    assertEquals(AccountType.LIABILITY, account.type());
  }

  /** A regra de formato da moeda ficou no `Account`; o comando não a duplica. */
  @Test
  void invalidCurrencyIsRejectedByTheDomain() {
    assertThrows(IllegalArgumentException.class,
            () -> new CreateAccountCommand("Caixa", "bitcoin", AccountType.ASSET).toAccount());
  }

  @Test
  void rejectsMissingFields() {
    assertThrows(NullPointerException.class, () -> new CreateAccountCommand(null, "BRL", AccountType.ASSET));
    assertThrows(NullPointerException.class, () -> new CreateAccountCommand("Caixa", null, AccountType.ASSET));
    assertThrows(NullPointerException.class, () -> new CreateAccountCommand("Caixa", "BRL", null));
  }
}
