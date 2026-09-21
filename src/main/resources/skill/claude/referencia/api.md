# API do sap-reports — referência

## Endereço

Base: `http://<host>/api/v1`. Em desenvolvimento o serviço sobe na **porta 2030**
(`SERVER_PORT`), então a base local é `http://localhost:2030/api/v1`. Atrás do portal,
costuma ser `https://<portal>/api/sap-reports/api/v1` — a tela de tokens mostra o
endereço exato que o front usa, junto do token.

## Autenticação — dois casos

Sempre no header `Authorization`, e o prefixo `Bearer ` é **opcional** (o serviço o
remove se vier):

| Quem chama | O que envia |
| --- | --- |
| **Agente de IA** | token de serviço: `Authorization: Bearer rpt_...` |
| Usuário pela tela | JWT do Keycloak, que o front injeta |

O token de serviço (`rpt_`) vale até 90 dias, valida, cria rascunho, gera prévia e
lê o catálogo — mas **não publica, não faz rollback e não gerencia tokens**: nesses
casos a resposta é 403, por desenho.

> Atenção: a regra "`Authorization` sem `Bearer`" vale para o **sap-rovema**, não para
> este serviço. Aqui as duas formas funcionam.

Autoria (`/admin/**`) exige o papel `admin` — ou um token de serviço, dentro dos
limites acima.

## Descoberta

| Método | Rota | Para que |
| --- | --- | --- |
| `GET` | `/admin/schema` | JSON Schema da definição (contrato do `.yaml`) |
| `GET` | `/admin/exemplos` | exemplos prontos: `definicao` e `template` de cada um |
| `GET` | `/admin/skill` | baixa este pacote de instruções em .zip |

## Autoria

| Método | Rota | O que faz |
| --- | --- | --- |
| `GET` | `/admin/schema` | JSON Schema da definição |
| `POST` | `/admin/relatorios/validar` | valida **sem gravar** |
| `POST` | `/admin/relatorios` | cria rascunho (versão 1) |
| `PUT` | `/admin/relatorios/{id}` | nova versão (rascunho) |
| `GET` | `/admin/relatorios` | lista todos, inclusive rascunhos |
| `GET` | `/admin/relatorios/{id}?versao=N` | devolve `definicao` e `template` em texto |
| `POST` | `/admin/relatorios/{id}/preview?formato=html\|pdf\|csv` | renderiza sem publicar |
| `POST` | `/admin/relatorios/{id}/publicar` | corpo `{"versao": N}` |
| `GET` | `/admin/relatorios/{id}/versoes` | histórico |
| `POST` | `/admin/relatorios/{id}/rollback/{versao}` | move o ponteiro de volta |
| `GET` | `/admin/relatorios/{id}/auditoria` | quem fez o quê |
| `DELETE` | `/admin/relatorios/{id}` | remove tudo |

Corpo de `validar`, `POST` e `PUT`:

```json
{ "definicao": "nome: X\npapeis: [admin]\n...", "template": "<h1>{{titulo}}</h1>..." }
```

O `preview` e o `render` recebem os valores dos parâmetros:

```json
{ "params": { "dataInicio": "2026-01-01", "dataFim": "2026-01-31" } }
```

As chaves de `params` correspondem a `parametros[].nome`. Chaves desconhecidas e
obrigatórios ausentes sem padrão dão 400. Omissão aplica o padrão; opcionais sem padrão usam null.

Cadastros: `filial` e `vendedor` recebem códigos inteiros; `parceiro_negocio`, `item`
e `localidade` recebem códigos textuais. `multiplo: true` recebe arrays não vazios
(também disponível para `lista`). Exemplo:

```json
{ "params": { "filiais": [1, 2], "slpCode": 7, "cardCode": "C0001" } }
```

Use `IN (:filiais)` no SQL. Opcional sem seleção envia null, que não significa “todos”.
Não envie objetos do cadastro, arrays vazios ou códigos separados por vírgula.
Os seletores usam APIs existentes do portal, não há endpoint novo de SQL de opções.

## Consumo (qualquer papel com interseção em `papeis[]`)

| Método | Rota |
| --- | --- |
| `GET` | `/relatorios` — lista os publicados visíveis ao usuário |
| `GET` | `/relatorios/{id}` — metadados e spec de parâmetros |
| `POST` | `/relatorios/{id}/render?formato=pdf\|html\|csv` |

## Códigos de erro

| HTTP | `erro` | O que fazer |
| --- | --- | --- |
| 400 | `parametros_invalidos` | corrigir as chaves de `params` |
| 401 | `nao_autorizado` | token ausente/expirado |
| 403 | `proibido` | falta o papel `admin` |
| 404 | `nao_encontrado` | id ou versão inexistente |
| 422 | `validacao_falhou` | corrigir `problemas[]` e reenviar |
| 422 | `resultado_truncado` | restringir o período — **não** insistir |
| 504 | — | consulta excedeu o tempo; simplificar ou restringir |

## Ciclo de vida

Versão é **imutável**. `PUT` cria uma nova; publicar apenas move o ponteiro
`versaoPublicada`; `rollback` move de volta. Nada é sobrescrito, e a auditoria registra
autor e horário de cada ação.

### Como ler o estado de um relatório

Três campos, que respondem coisas diferentes:

| Campo | Significa |
| --- | --- |
| `ultimaVersao` | a versão mais recente que existe (a que o `PUT` acabou de criar) |
| `versaoPublicada` | a versão que os usuários enxergam hoje; `null` = nenhuma |
| `status` | `PUBLICADO` se existe alguma versão publicada, senão `RASCUNHO` |

Por isso, ao criar a versão 2 de um relatório já publicado, a resposta vem
`status: PUBLICADO`, `ultimaVersao: 2`, `versaoPublicada: 1`. **Não significa que a
versão 2 foi publicada** — ela é um rascunho, e os usuários continuam vendo a 1 até
alguém publicar.

Regra prática: a versão N está no ar somente quando `versaoPublicada == N`. Em
`GET /admin/relatorios/{id}/versoes` cada item traz `publicada: true|false`, que é a
mesma informação por versão.

Prévia (`/preview`) roda **qualquer** versão, publicada ou não; é assim que se confere
um rascunho sem expor nada a ninguém.
