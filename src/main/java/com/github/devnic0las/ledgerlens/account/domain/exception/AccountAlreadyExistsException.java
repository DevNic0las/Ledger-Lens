package com.github.devnic0las.ledgerlens.account.domain.exception;

/**
 * Já existe uma conta com o mesmo {@code (name, currency)}. Espelha a constraint
 * {@code account_name_currency_key} do banco, que é a fonte de verdade da unicidade.
 */
public class AccountAlreadyExistsException extends RuntimeException {

  public AccountAlreadyExistsException(String message) {
    super(message);
  }
}
