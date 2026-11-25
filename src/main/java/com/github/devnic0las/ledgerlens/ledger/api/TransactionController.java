package com.github.devnic0las.ledgerlens.ledger.api;

import com.github.devnic0las.ledgerlens.ledger.application.PostTransactionService;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /transactions}: registra um lançamento de dupla entrada.
 *
 * <ul>
 *   <li>{@code 201} — gravado agora.</li>
 *   <li>{@code 200} — a mesma chave já tinha sido gravada com conteúdo idêntico; o corpo é a
 *       transação <b>original</b>, com os ids dela.</li>
 *   <li>{@code 409} — a chave existe com conteúdo diferente. Não devolve a original em
 *       silêncio: seria esconder que o cliente reenviou outro pedido com a mesma chave.</li>
 *   <li>{@code 422} — pedido interpretável que viola regra de negócio (desbalanceado, menos de
 *       dois lançamentos, valor não positivo, conta inexistente).</li>
 *   <li>{@code 400} — pedido não interpretável (JSON inválido, campo obrigatório ausente,
 *       {@code Idempotency-Key} ausente).</li>
 * </ul>
 *
 * <p><b>Contrato da idempotência:</b> a identidade é o header {@code Idempotency-Key} mais a
 * descrição e o multiconjunto de {@code (conta, direção, valor)}. A descrição é comparada
 * <b>byte a byte</b>, sem {@code trim} e sem normalização: {@code "Pix"} e {@code "Pix "} são
 * pedidos diferentes. Um retry legítimo precisa repetir a descrição exatamente como enviada.
 */
@RestController
@RequestMapping("/transactions")
class TransactionController {

  private final PostTransactionService service;

  TransactionController(PostTransactionService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<TransactionResponse> post(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @Valid @RequestBody PostTransactionRequest request) {
    var result = service.post(request.toCommand(idempotencyKey));

    return result.result() == PostResult.POSTED
            ? ResponseEntity.status(201).body(TransactionResponse.of(result.transaction()))
            : ResponseEntity.ok(TransactionResponse.of(result.transaction()));
  }
}
