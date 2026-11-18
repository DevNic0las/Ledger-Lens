Regras de trabalho neste repositório (ledgerlens):
- A camada de domínio (subpacotes account/domain e ledger/domain) é Java puro, sem Spring, SQL ou persistência. Application e infrastructure podem usar Spring. A dependência aponta sempre para o domínio. Não altere regras de domínio sem eu pedir.
- Uma tarefa = uma branch = um PR pequeno. Não misture mudanças.
- Teste primeiro: mostre o teste falhando, depois a implementação, depois passando.
- Antes de cada PR, explique em até 5 linhas: o que mudou, por que, e qual alternativa você descartou.
- Dinheiro sempre em long (centavos). Nunca double.
- Ao terminar, me faça 2 perguntas sobre o que você implementou, para eu confirmar que entendi.
- Rode ./mvnw test e mostre o resultado antes de dizer que terminou.
- Não faça perguntas sobre nomes, formatação ou artefatos auxiliares. Decida pelo mais simples e siga em frente. Pergunte só quando a decisão afetar o design, o escopo ou as regras do domínio.