package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;

import java.util.UUID;

/**
 * Pedido de criação de conta. Não carrega {@code id}: o servidor gera, então o cliente não
 * pode colidir com uma PK existente nem insistir num id já usado.
 *
 * <p>Aqui só se valida presença e nulos. O formato da moeda e o nome em branco são regra do
 * {@link Account}, que é o construtor do agregado — duplicar a regra aqui criaria duas fontes
 * de verdade.
 */
public record CreateAccountCommand(String name, String currency, AccountType type) {

  public CreateAccountCommand {
    if (name == null) {
      throw new NullPointerException("Account name is required");
    }
    if (currency == null) {
      throw new NullPointerException("Account currency is required");
    }
    if (type == null) {
      throw new NullPointerException("Account type is required");
    }
  }

  /** O id nasce no servidor, uma vez por pedido. */
  public Account toAccount() {
    return new Account(UUID.randomUUID(), name, currency, type);
  }
}
