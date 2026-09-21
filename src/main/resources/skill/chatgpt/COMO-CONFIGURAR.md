# Configurar um GPT personalizado para criar relatórios

No ChatGPT **não existe "instalar skill"** — isso é do Claude Code. O equivalente,
e que funciona melhor, é um **GPT personalizado com Action**: ele chama a API de
verdade, em vez de só ler instruções.

## Passo a passo

1. ChatGPT → **Explorar GPTs** → **Criar** → aba **Configurar**
2. **Instructions**: cole o conteúdo de `INSTRUCOES.md` (deste pacote)
3. **Knowledge**: envie `exemplos/vendas-por-cliente.yaml` e `.hbs`, e
   `referencia/regras.md`
4. **Actions** → **Create new action** → **Import from file**: envie
   `openapi-actions.yaml`
5. **Authentication** → **API Key**
   - Auth Type: `API Key`
   - API Key: o token gerado na tela de relatórios (começa com `rpt_`)
   - Auth Type (header): `Bearer`
6. Salve e teste pedindo: *"liste os relatórios disponíveis"*

## O que o token pode e não pode

O token de serviço **cria rascunho, valida e pré-visualiza**. Ele **não publica**
e não remove — isso é 403 por desenho.

O motivo: publicar decide quem passa a enxergar faturamento. É decisão de
negócio, e fica com uma pessoa. O fluxo esperado é a IA preparar e alguém liberar.

Ele também não gerencia tokens: um token que emitisse outro tornaria a expiração
inútil.

## Requisito de infraestrutura

Para o ChatGPT (que roda na nuvem da OpenAI) chamar sua API, o `sap-reports`
precisa estar **acessível pela internet**, com HTTPS.

Isso muda o modelo de ameaça do sistema. Se for por esse caminho:

- exponha **apenas** o `sap-reports` — nunca o `sap-odbc`, que é o gateway com
  acesso amplo ao banco;
- mantenha a expiração curta no token;
- revogue o token assim que o GPT sair de uso.

Se a exposição à internet não for aceitável, use o Claude Code com a skill em
`claude/`, que roda na sua máquina e alcança a rede interna.
