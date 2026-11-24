package com.github.devnic0las.ledgerlens.ledger.application;

import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;

/**
 * Resultado do caso de uso. {@code POSTED} carrega a transação recém-gravada; {@code DUPLICATE}
 * carrega a transação original que já ocupava a chave — nunca a tentativa repetida.
 */
public record PostTransactionResult(Transaction transaction, PostResult result) {
}
