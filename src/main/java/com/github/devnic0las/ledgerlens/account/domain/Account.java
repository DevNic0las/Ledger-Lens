package com.github.devnic0las.ledgerlens.account.domain;

import java.util.UUID;

/**
 * Conta contábil. {@code AccountType} decide o lado normal (débito ou crédito), e o par
 * {@code (name, currency)} identifica a conta para quem a usa — a mesma unicidade existe no
 * banco, na constraint {@code account_name_currency_key}.
 *
 * <p>A validação mora no construtor de propósito: conta inválida não pode ser construída por
 * nenhum caminho, venha ela de um controller ou de um adaptador futuro.
 */
public record Account(UUID id, String name, String currency, AccountType type) {

  public Account {
    if (id == null) {
      throw new NullPointerException("Account id is required");
    }
    if (name == null) {
      throw new NullPointerException("Account name is required");
    }
    if (name.isBlank()) {
      throw new IllegalArgumentException("Account name must not be blank");
    }
    if (currency == null) {
      throw new NullPointerException("Account currency is required");
    }
    if (!currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException(
              "Account currency must be three upper-case letters (ISO 4217): " + currency);
    }
    if (type == null) {
      throw new NullPointerException("Account type is required");
    }
  }
}
