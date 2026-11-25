package com.github.devnic0las.ledgerlens.ledger.domain.exception;

/**
 * Regra de valor positivo de um lançamento violada: {@code amountCents <= 0}.
 *
 * <p>Separada de {@link InvalidTransactionException} porque a violação é da
 * {@link com.github.devnic0las.ledgerlens.ledger.domain.Entry}, não do fechamento da
 * transação. As duas são 422, mas o diagnóstico não se confunde.
 *
 * <p>Estende {@link IllegalArgumentException} para preservar o contrato existente.
 */
public class InvalidEntryException extends IllegalArgumentException {

  public InvalidEntryException(String message) {
    super(message);
  }
}
