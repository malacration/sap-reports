# Servidor MCP do sap-reports

O `sap-reports` é também um servidor **MCP** (Model Context Protocol). Em vez de a
IA montar `curl` a partir das instruções da skill, ela recebe as operações como
**ferramentas** — mesmas regras, mesmo validador, mesmo token.

## Endereço e autenticação

| | |
| --- | --- |
| URL | `<base da API>/admin/mcp` — ex.: `https://<portal>/reports/api/v1/admin/mcp` |
| Transporte | HTTP (Streamable HTTP, sem sessão) |
| Cabeçalho | `Authorization: Bearer rpt_...` |

A tela **Tokens para IA** mostra, ao gerar o token, a URL exata e o comando pronto.

## Claude Code

```bash
claude mcp add --transport http sap-reports <URL> \
  --header "Authorization: Bearer rpt_..."
```

## Outros clientes (`.mcp.json` e equivalentes)

```json
{
  "mcpServers": {
    "sap-reports": {
      "type": "http",
      "url": "<URL>",
      "headers": { "Authorization": "Bearer rpt_..." }
    }
  }
}
```

Cliente na nuvem (ChatGPT, claude.ai) só alcança o servidor se o `sap-reports`
estiver exposto na internet — vale o mesmo alerta de `chatgpt/COMO-CONFIGURAR.md`.

## Ferramentas

| Ferramenta | O que faz |
| --- | --- |
| `guia` | formato do YAML/template e regras do validador — chamar uma vez |
| `schema` | JSON Schema da definição |
| `listar_exemplos` | exemplos prontos (definição + template) |
| `validar_relatorio` | valida **sem gravar**, devolve todos os problemas |
| `criar_relatorio` | cria rascunho (versão 1) |
| `atualizar_relatorio` | nova versão, como rascunho |
| `listar_relatorios` / `obter_relatorio` / `listar_versoes` | leitura do catálogo |
| `preview_relatorio` | pede a execução de qualquer versão e devolve o id **na hora** |
| `resultado_preview` | estado da execução; quando pronta, o conteúdo (html, csv ou pdf) |

**Publicar, rollback e remover não são ferramentas.** A IA prepara o rascunho;
uma pessoa confere e publica pela tela.

## Testar sem cliente

```bash
curl -s <URL> -H "Authorization: Bearer rpt_..." -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```
