package com.github.devnic0las.ledgerlens.account.domain.exception;

/**
 * A conta pedida numa leitura não existe. Diferente de
 * {@link com.github.devnic0las.ledgerlens.ledger.domain.exception.AccountsNotFoundException},
 * que trata contas citadas numa transação: aqui é um único recurso lido diretamente pela URL,
 * logo a resposta é 404.
 *
 * <p>É lançada pelo serviço de saldo quando a porta devolve
 * {@link java.util.OptionalLong#empty()}. Nunca nasce de {@code IllegalArgumentException}:
 * ausência de conta é um valor explícito, e uma falha de infraestrutura precisa subir como
 * 500 em vez de ser reportada como 404.
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
