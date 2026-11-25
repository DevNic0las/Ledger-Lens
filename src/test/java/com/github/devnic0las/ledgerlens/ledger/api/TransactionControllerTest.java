package com.github.devnic0las.ledgerlens.ledger.api;

import com.github.devnic0las.ledgerlens.ledger.application.PostTransactionService;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.AccountsNotFoundException;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.InvalidTransactionException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tradução de exceção do domínio para status HTTP. O serviço é mockado de propósito: o que se
 * mede aqui é a camada de borda, não a regra de idempotência (que já tem testes próprios).
 */
@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

  private static final String IDEMPOTENCY_KEY = "chave-1";

  private static final String BODY = """
          {
            "description": "Pix",
            "entries": [
              {"accountId": "11111111-1111-1111-1111-111111111111", "direction": "DEBIT",  "amountCents": 10000},
              {"accountId": "22222222-2222-2222-2222-222222222222", "direction": "CREDIT", "amountCents": 10000}
            ]
          }
          """;

  @Autowired
  MockMvc mvc;

  @MockitoBean
  PostTransactionService service;

  @Test
  void idempotencyConflictBecomes409() throws Exception {
    when(service.post(any())).thenThrow(new IdempotencyConflictException("chave reutilizada"));

    mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.title").value("Idempotency key conflict"));
  }

  /** A resposta precisa listar QUAIS ids faltam, não só dizer que faltou conta. */
  @Test
  void missingAccountsBecome422ListingTheIds() throws Exception {
    var missing = UUID.fromString("33333333-3333-3333-3333-333333333333");
    when(service.post(any())).thenThrow(new AccountsNotFoundException(Set.of(missing)));

    mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.missingAccountIds[0]").value(missing.toString()));
  }

  /** Regra contábil violada é pedido interpretável: 422, não 400. */
  @Test
  void unbalancedTransactionBecomes422() throws Exception {
    when(service.post(any())).thenThrow(new InvalidTransactionException("Transaction is unbalanced"));

    mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.title").value("Business rule violated"));
  }

  /**
   * Prova que o mapeamento não é amplo: um {@code IllegalArgumentException} que não é de
   * negócio é bug de programação e não pode virar 422 — isso mentiria para o cliente dizendo
   * que o pedido dele estava errado.
   *
   * <p>Aqui a exceção propaga pelo MockMvc, que não faz error dispatch; num servidor real ela
   * vira 500 pelo tratamento padrão. O que este teste fixa é a ausência do mapeamento amplo,
   * não o formato do 500.
   *
   * <p>Não há {@code @ExceptionHandler(Exception.class)} de propósito: ele capturaria também
   * {@code NoResourceFoundException} e transformaria "rota não existe" (404) em 500.
   */
  @Test
  void genericIllegalArgumentIsNotTranslatedInto422() {
    when(service.post(any())).thenThrow(new IllegalArgumentException("bug de programação qualquer"));

    assertThrows(ServletException.class, () ->
            mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)));
  }

  /** Sem chave não há como deduplicar: o pedido nem chega ao serviço. */
  @Test
  void missingIdempotencyKeyBecomes400() throws Exception {
    mvc.perform(post("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400));

    verifyNoInteractions(service);
  }

  @Test
  void postedBecomes201WithTheTransaction() throws Exception {
    when(service.post(any())).thenReturn(TestPayloads.posted());

    mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.description").value("Pix"))
            .andExpect(jsonPath("$.entries.length()").value(2));
  }

  @Test
  void duplicateBecomes200WithTheOriginalTransaction() throws Exception {
    when(service.post(any())).thenReturn(TestPayloads.duplicate());

    mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(TestPayloads.ORIGINAL_ID.toString()));
  }

  @Test
  void malformedJsonBecomes400() throws Exception {
    mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"description\": "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));

    verifyNoInteractions(service);
  }

  /** Corpo interpretável mas incompleto é 400: é forma, não regra de negócio. */
  @Test
  void missingRequiredFieldBecomes400() throws Exception {
    mvc.perform(post("/transactions")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"entries\": []}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));

    verifyNoInteractions(service);
  }
}
