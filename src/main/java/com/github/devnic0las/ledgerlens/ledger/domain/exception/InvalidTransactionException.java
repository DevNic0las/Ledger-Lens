package com.github.devnic0las.ledgerlens.ledger.domain.exception;

/**
 * Violação de regra contábil na construção de uma transação: menos de dois lançamentos,
 * débitos diferentes de créditos.
 *
 * <p>Existe para que a borda HTTP saiba distinguir uma regra de negócio violada de um
 * {@link IllegalArgumentException} qualquer. Mapear todo {@code IllegalArgumentException} para
 * 422 esconderia bug de programação como erro do cliente.
 *
 * <p>Estende {@link IllegalArgumentException} para preservar o contrato que os testes e o
 * {@code Ledger} em memória já esperam.
 */
public class InvalidTransactionException extends IllegalArgumentException {

  public InvalidTransactionException(String message) {
    super(message);
  }
}
