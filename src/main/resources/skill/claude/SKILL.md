---
name: report-skill
description: Criar e atualizar relatórios no sap-reports via API. Use quando pedirem para criar, alterar, validar ou publicar um relatório de vendas/faturamento/clientes — ou quando alguém disser "cria um relatório de X". Cobre o formato YAML+Handlebars, as regras do validador e o ciclo validar→corrigir→publicar.
---

# Criar relatórios no sap-reports

O `sap-reports` recebe relatórios **por API, em runtime** — sem commit, sem deploy.
Um relatório são **dois arquivos de texto**: um `.yaml` (consulta e metadados) e um
`.hbs` (apresentação em Handlebars).

## O ciclo — sempre nesta ordem

```
1. GET  /api/v1/admin/schema              lê o JSON Schema (uma vez por sessão)
2. POST /api/v1/admin/relatorios/validar  valida SEM gravar
3.      422 → corrija e volte ao passo 2  (repita até passar)
4. POST /api/v1/admin/relatorios          cria o rascunho → a resposta traz o `id` (número)
5. POST /api/v1/admin/relatorios/{id}/preview?formato=html   confira o resultado
6.      publicar é decisão HUMANA — veja "Limites" no fim
```

**Nunca pule o passo 2.** Ele não grava nada e devolve *todos* os erros de uma vez.
Mandar direto para o passo 4 desperdiça tentativa e suja o histórico de versões.

O `id` é gerado pelo banco — **não** vai no YAML. Guarde o número devolvido na criação.

Para **atualizar** um relatório existente: `PUT /api/v1/admin/relatorios/{id}` com o
mesmo corpo. Isso cria uma versão nova como rascunho; a versão publicada continua
servindo os usuários até alguém publicar a nova.

## Corpo da requisição

```json
{ "definicao": "<conteúdo do .yaml>", "template": "<conteúdo do .hbs>" }
```

## Como corrigir um 422

A resposta é legível por máquina — use os três campos, não só a mensagem:

```json
{ "erro": "validacao_falhou",
  "problemas": [
    { "caminho": "consulta.sql", "regra": "tabela_nao_permitida",
      "mensagem": "A tabela 'SYS.USERS' nao esta na lista permitida." },
    { "caminho": "template", "regra": "escape_desabilitado",
      "mensagem": "Uso de '{{{' na linha 12.", "linha": 12 }
  ] }
```

`caminho` = onde no YAML · `regra` = código estável · `linha` = posição no template.

Corrija **todos** antes de reenviar. Se a mesma `regra` voltar duas vezes seguidas,
pare e pergunte — não tente variações às cegas.

## Regras que o validador aplica

Estas são as checagens reais do serviço. Violar qualquer uma devolve 422.

### SQL

- Começa com `SELECT` ou `WITH`. Nada de `INSERT`/`UPDATE`/`DELETE`/`CALL`.
- **Uma instrução só.** Sem `;` no meio.
- **Sem comentários** — nem `--`, nem `/* */`. O gateway os recusa.
- Tabelas **qualificadas com schema**: `SBOGRUPOROVEMA.OINV`, nunca `OINV`.
- Tabelas precisam estar na allowlist configurada (`reports.allowed-tables`).
- Todo valor variável vira `:parametro`. **Nunca** concatene valor no texto do SQL.
- Cada `:nome` do SQL precisa estar declarado em `parametros[]`, e cada declarado
  precisa aparecer no SQL. Sobrar ou faltar dá erro.

### Template Handlebars

- Use **`{{ variavel }}`**. As formas `{{{ }}}` e `{{& }}` são **proibidas** (não
  escapam HTML → XSS).
- Tags proibidas: `script`, `iframe`, `object`, `embed`, `applet`, `form`, `base`,
  `meta`, `link`.
- Atributos de evento (`onclick`, `onerror`, …) são proibidos.
- `src`, `poster`, `data` e `url()` no CSS só aceitam **`data:` URI**. Nada de
  `http://`, `https://` ou `file://` — o servidor buscaria o recurso ao gerar o PDF.
- `href` com `javascript:` é proibido.
- **Partials (`{{> algo}}`) são proibidos** — leriam arquivo do servidor.
- Dados chegam em `linhas` (array), `colunas`, `params`, `meta`, `totais`, `grupos` e `resumos`
  (veja "Agrupamento e totais").

## Formato do `.yaml`

Campos desconhecidos são **recusados** — erro de digitação não passa em silêncio.

```yaml
nome: Vendas por cliente         # título exibido; não precisa ser único
descricao: Total faturado por cliente no período    # opcional
papeis: [vendedor, admin]       # quem enxerga; ao menos um
parametros:
  - nome: dataInicio            # precisa casar com :dataInicio no SQL
    tipo: date                  # texto | numero | date | datetime | booleano | lista
    rotulo: Data inicial        # opcional; sem ele a tela usa `nome`
    obrigatorio: true
consulta:
  sql: >
    SELECT T0.CardCode, T0.CardName, SUM(T0.DocTotal) AS TOTAL
    FROM SBOGRUPOROVEMA.OINV T0
    WHERE T0.DocDate >= :dataInicio
    GROUP BY T0.CardCode, T0.CardName
  maxRows: 5000                 # ≤ limite do serviço
colunas:
  - campo: CardCode             # nome da coluna que a consulta devolve
    titulo: Código
    tipo: texto                 # texto | numero | moeda | data | datahora | percentual
    alinhamento: esquerda       # opcional: esquerda | centro | direita
formatos: [pdf, html, csv]
```

Primitivos: `texto` recebe string, `numero` recebe número JSON (inteiro ou decimal,
sem separar int/float), `date` recebe data ISO YYYY-MM-DD, `datetime` recebe data/hora ISO
e `booleano` recebe true/false. Não use os aliases string/int/float no YAML.
Parâmetro `tipo: lista` exige `opcoes: [{valor, rotulo}]`.

## Agrupamento e totais

O template **não faz conta** (Handlebars é logic-less). Subtotal e total geral são
calculados **no servidor**, em decimal exato, a partir dos valores brutos da consulta:

```yaml
agrupar: [VENDEDOR]            # campos de `colunas`, do grupo externo ao interno (máx. 3)
colunas:
  - { campo: VENDEDOR, titulo: Vendedor, tipo: texto }
  - { campo: NOTAS, titulo: Notas, tipo: numero, total: sum }
  - { campo: TOTAL, titulo: Total, tipo: moeda, total: sum }
```

- `total` é a operação aplicada à coluna, em cada grupo e no total geral:
  `sum` (soma), `avg` (média), `min` (mínimo), `max` (máximo) — só em `numero`,
  `moeda`, `percentual` — ou `count` (qualquer tipo; conta valores não nulos).
- Todo campo de `agrupar` precisa estar em `colunas`, porque ele fornece o título e o formato.
- As linhas são reunidas **pelo valor** do campo: um grupo nunca se parte. O `ORDER BY`
  do SQL só define a **ordem** em que os grupos aparecem — comece-o pelos campos de
  `agrupar` para a ordem ficar previsível.

O template recebe, além de `linhas`:

| Variável | Conteúdo |
|---|---|
| `totais.CAMPO` | total geral já formatado (ex.: `R$ 1.234,56`) |
| `meta.quantidade` | número de linhas |
| `grupos` | um item por grupo, na ordem do SQL |
| `grupos[].titulo` / `valor` | título da coluna e valor do grupo (`Vendedor` / `Ana`) |
| `grupos[].quantidade` | linhas no grupo |
| `grupos[].totais.CAMPO` | subtotal do grupo |
| `grupos[].linhas` | as linhas do grupo |
| `grupos[].grupos` | subgrupos (próximo nível de `agrupar`; vazio no último) |

```handlebars
{{#each grupos}}
  <tr class="grupo"><td colspan="3">{{titulo}}: {{valor}}</td></tr>
  {{#each linhas}}<tr><td>{{NOME}}</td><td>{{NOTAS}}</td><td>{{TOTAL}}</td></tr>{{/each}}
  <tr class="subtotal"><td>Subtotal</td><td>{{totais.NOTAS}}</td><td>{{totais.TOTAL}}</td></tr>
{{/each}}
<tfoot><tr><td>Total geral</td><td>{{totais.NOTAS}}</td><td>{{totais.TOTAL}}</td></tr></tfoot>
```

No CSV, cada grupo termina numa linha `Subtotal …` e o arquivo numa linha `Total geral`,
**somente** quando alguma coluna tem `total`.

### Resumos: outro corte da mesma venda

O agrupamento é hierárquico: com `agrupar: [FILIAL, VENDEDOR]` o vendedor tem subtotal
**dentro** de cada filial, mas não existe o total dele somando todas as filiais. Para
isso use `resumos` — cortes independentes, com os mesmos `total` das colunas:

```yaml
agrupar: [FILIAL, VENDEDOR]
resumos:
  - titulo: Total por vendedor (todas as filiais)   # opcional; padrão "Resumo por Vendedor"
    por: [VENDEDOR]                                 # 1 a 3 campos de `colunas`
```

- Máximo de 5 resumos; exige ao menos uma coluna com `total`.
- Grupos ordenados pelo valor (alfabético, ou numérico), independente do `ORDER BY`.
- No template: `resumos[].titulo` e `resumos[].grupos` (mesma forma de `grupos`:
  `valor`, `quantidade`, `totais.CAMPO`, `linhas`, `grupos`).

```handlebars
{{#each resumos}}
  <h2>{{titulo}}</h2>
  <table>{{#each grupos}}<tr><td>{{valor}}</td><td>{{totais.TOTAL}}</td></tr>{{/each}}</table>
{{/each}}
```

No CSV, cada resumo vira um bloco depois do `Total geral`, uma linha por grupo
(`Vendedor: Ana`, e em dois níveis `Vendedor: Ana / Filial: Matriz`).

Helpers de formatação disponíveis: `{{moeda x}}`, `{{numero x}}`, `{{percentual x}}`,
`{{data x}}`. Colunas declaradas em `colunas` já chegam formatadas; os helpers servem
para `params` e valores fora de `colunas`.

## Logo no cabeçalho

O serviço **nunca busca imagem por URL** (seria SSRF no gerador de PDF). A logo vai
embutida no pedido de geração: o front envia o `logo.png` dele como `data:` URI no
campo `logo` do corpo, e o template a recebe em `{{meta.logo}}`:

```handlebars
{{#if meta.logo}}<img class="logo" src="{{meta.logo}}" alt="Logo">{{/if}}
```

`{{meta.logo}}` é a **única** expressão aceita em `src`; qualquer outra é recusada pelo
validador. Sempre use `{{#if meta.logo}}`: em chamadas sem logo (as suas, por exemplo)
o campo não existe e um `<img>` vazio apareceria quebrado.

## Campos de cadastro e seleção múltipla

O front gera os campos a partir de `parametros[]`. Escolha o `tipo` explicitamente;
o nome do parâmetro não ativa uma busca. Reutilize somente os cadastros existentes.
Não adicione SQL de opções, URL, endpoint ou configuração `busca` ao YAML.

| tipo | Cadastro / valor enviado ao SQL |
| --- | --- |
| `filial` | filiais, código inteiro `Bplid/BPLID` |
| `vendedor` | vendedores, inteiro `SalesEmployeeCode` (corresponde a `SlpCode`) |
| `parceiro_negocio` | parceiros, texto `CardCode` |
| `item` | itens, texto `ItemCode` |
| `localidade` | localidades, texto `Code` |

Sem `multiplo` (ou com `false`), envie um código escalar e use `= :nome`.
Com `multiplo: true`, envie um array JSON não vazio de códigos e use `IN (:nome)`.
Também funciona para `tipo: lista`, que continua exigindo `opcoes`.
Não use `multiplo` com texto, número, datas ou booleanos.
Nunca envie objetos de cadastro, nomes de exibição, CSV de códigos ou SQL interpolado.

```yaml
parametros:
  - nome: filiais
    tipo: filial
    rotulo: Filiais
    multiplo: true
    obrigatorio: true
  - nome: slpCode
    tipo: vendedor
    rotulo: Vendedor
    obrigatorio: true
  - nome: cardCode
    tipo: parceiro_negocio
    rotulo: Parceiro de negócio
    obrigatorio: true
```

Exemplo de valores para preview/render:
`{"params":{"filiais":[1,2],"slpCode":7,"cardCode":"C0001"}}`.
Os códigos são ilustrativos: busque valores reais nos cadastros antes do preview.
Exemplo de filtro: `T0."BPLId" IN (:filiais) AND T0."SlpCode" = :slpCode AND T0."CardCode" = :cardCode`.
Os JOINs continuam no SQL do relatório; confirme a chave na tabela/alias usado.
Para localidade, confirme a relação real com o cadastro; não presuma um campo em OINV.

`padrao` deve ter o mesmo tipo/cardinalidade do parâmetro (ex.: `[1,2]` para filiais múltiplas).
Obrigatórios exigem seleção. Opcional vazio envia `null`, nunca `[]`.
`IN (NULL)` não retorna linhas; vazio não significa “todos”.
Para oferecer “todos” com array, use um booleano separado no SQL
(`:todos = TRUE OR T0."BPLId" IN (:filiais)`), declarando ambos.
Não use `:filiais IS NULL` para testar um array: a expansão dos binds pode produzir SQL inválido.
Seletores são filtros, não uma barreira de autorização nem validação da existência dos códigos.
Um novo cadastro exige implementação no registro do front e no contrato do backend; não invente tipos.

## Referências

- `referencia/api.md` — endpoints, corpos e códigos de erro
- `referencia/regras.md` — as 13 regras do validador, com exemplo de violação
- `exemplos/vendas-por-cliente.yaml` e `.hbs` — filtros de datas
- `exemplos/vendas-por-cadastro.yaml` e `.hbs` — filiais múltiplas, vendedor e parceiro com JOIN
- `exemplos/vendas-agrupadas-por-vendedor.yaml` e `.hbs` — agrupamento, subtotal e total geral
- `exemplos/vendas-por-filial-e-vendedor.yaml` e `.hbs` — dois níveis: filial > vendedor, com total da filial e resumo por vendedor

## Limites — leia antes de publicar

**Não publique sozinho.** `POST /publicar` torna o relatório visível a todos os papéis
listados em `papeis[]`. Crie o rascunho, gere o preview, e **entregue o link para uma
pessoa decidir**. O motivo é concreto: o SQL do relatório lê dados reais, e escolher
`papeis` errado expõe faturamento a quem não deveria ver. Essa é uma decisão de negócio.

**Não contorne a allowlist.** Se uma tabela necessária não estiver liberada, diga isso —
não procure outra tabela que "dê quase o mesmo número". Um relatório aproximado é pior
que relatório nenhum, porque ninguém sabe que é aproximado.

**Não invente nomes de coluna do SAP.** Se não tiver certeza de que `OINV` tem o campo
que você quer, pergunte ou consulte o dicionário — não chute. Campo errado ou dá erro
(bom) ou traz número errado (péssimo).
