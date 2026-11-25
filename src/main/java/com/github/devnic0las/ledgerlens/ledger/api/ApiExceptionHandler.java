package com.github.devnic0las.ledgerlens.ledger.api;

import com.github.devnic0las.ledgerlens.ledger.domain.exception.AccountsNotFoundException;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;

/**
 * Tradução de exceção para HTTP, no formato RFC 9457 ({@code application/problem+json}).
 *
 * <p>O critério não é o tipo da exceção, é a natureza da falha:
 * <ul>
 *   <li><b>400</b> — pedido não interpretável: JSON inválido, campo obrigatório ausente,
 *       header ausente, valor que não converte.</li>
 *   <li><b>422</b> — pedido interpretável que viola regra de negócio: desbalanceado, menos de
 *       dois lançamentos, conta inexistente.</li>
 *   <li><b>409</b> — pedido válido que conflita com estado existente: chave de idempotência
 *       reutilizada com outro conteúdo, conta com nome e moeda já existentes.</li>
 *   <li><b>404</b> — recurso lido não existe.</li>
 *   <li><b>500</b> — bug: colisão de PK, estado impossível.</li>
 * </ul>
 */
@RestControllerAdvice
class ApiExceptionHandler {

  /** Regra contábil violada no construtor do domínio. */
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail businessRuleViolated(IllegalArgumentException exception) {
    var problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violated", exception.getMessage());
    return problem;
  }

  /** Lista exatamente os ids que faltam, para que o cliente saiba o que corrigir. */
  @ExceptionHandler(AccountsNotFoundException.class)
  ProblemDetail accountsNotFound(AccountsNotFoundException exception) {
    var problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Accounts not found",
            "Some accounts referenced by the transaction do not exist");
    problem.setProperty("missingAccountIds", exception.missingAccountIds());
    return problem;
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ProblemDetail idempotencyConflict(IdempotencyConflictException exception) {
    return problem(HttpStatus.CONFLICT, "Idempotency key conflict", exception.getMessage());
  }

  /** JSON malformado ou valor que não converte para o tipo do campo. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail unreadableBody(HttpMessageNotReadableException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Malformed request body",
            "The request body could not be read as valid JSON for this endpoint");
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ProblemDetail missingHeader(MissingRequestHeaderException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Missing required header",
            "Required header is missing: " + exception.getHeaderName());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail argumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
            "Parameter could not be converted: " + exception.getName());
  }

  /** Bean Validation: campo obrigatório ausente, texto em branco, lista vazia. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail invalidBody(MethodArgumentNotValidException exception) {
    var problem = problem(HttpStatus.BAD_REQUEST, "Invalid request body",
            "One or more fields are missing or invalid");
    problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList());
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
