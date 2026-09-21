# As 13 regras do validador

Cada `regra` do 422 corresponde a uma checagem. Códigos são estáveis — use-os para
decidir a correção, não o texto da mensagem.

| `regra` | O que violou | Correção |
| --- | --- | --- |
| `schema_invalido` | campo obrigatório faltando, tipo inválido ou **campo desconhecido** | conferir o nome do campo; typo não passa |
| `parametro_ausente` | o SQL usa `:x` não declarado | declarar em `parametros[]` |
| `parametro_nao_usado` | declarado e não usado no SQL | remover, ou usar no SQL |
| `nome_parametro_invalido` | nome fora de `[A-Za-z][A-Za-z0-9_]*` | renomear |
| `sql_com_comentario` | `--` ou `/* */` no SQL | remover — o gateway recusa |
| `sql_multiplas_instrucoes` | `;` separando instruções | uma consulta por relatório |
| `sql_nao_e_select` | não começa com `SELECT`/`WITH`, ou não parseia | reescrever como consulta |
| `tabela_nao_permitida` | tabela fora da allowlist | usar tabela liberada, ou pedir liberação |
| `template_nao_compila` | Handlebars inválido, ou uso de partial | corrigir sintaxe; partial é proibido |
| `escape_desabilitado` | `{{{ }}}` ou `{{& }}` | trocar por `{{ }}` |
| `tag_proibida` | `script`/`iframe`/`form`/…, `on*=`, `javascript:` | remover |
| `recurso_externo_proibido` | `src`/`href`/`url()` apontando para fora | só `data:` URI |
| `limite_excedido` | template/definição grandes demais, `maxRows` acima do teto | reduzir |

## Por que cada proibição existe

Não são preferências de estilo — o relatório é executado no servidor.

- **`{{{ }}}` e `{{& }}`** não escapam HTML. Um dado de cliente com `<script>` viraria
  XSS em quem abrir o relatório.
- **Recurso externo** faz o servidor buscar a URL ao gerar o PDF: `file:///etc/passwd`
  vira leitura de arquivo, e `http://10.0.0.1/` vira varredura da rede interna.
- **Partials** resolveriam caminho no disco do servidor.
- **Comentário no SQL** esconde trecho de quem audita a consulta.
- **Tabela fora da allowlist** é o que impede um relatório de despejar qualquer tabela
  do ERP.
