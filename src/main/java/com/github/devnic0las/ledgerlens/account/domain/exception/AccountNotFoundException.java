package com.github.devnic0las.ledgerlens.account.domain.exception;

/**
 * A conta pedida numa leitura não existe. Diferente de
 * {@link com.github.devnic0las.ledgerlens.ledger.domain.exception.AccountsNotFoundException},
 * que trata contas citadas numa transação: aqui é um único recurso lido diretamente pela URL,
 * logo a resposta é 404.
 *
 * <p>Existe para que a borda não dependa de um {@link IllegalArgumentException} ambíguo: o
 * {@code balanceOf} também o lança por outros motivos, e um 422 no lugar de 404 seria mentira.
 */
public class AccountNotFoundException extends RuntimeException {

  private final java.util.UUID accountId;

  public AccountNotFoundException(java.util.UUID accountId) {
    super("Account not found: " + accountId);
    this.accountId = accountId;
  }

  public java.util.UUID accountId() {
    return accountId;
  }
}
