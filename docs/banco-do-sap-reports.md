# Banco do sap-reports

O serviço usa **duas conexões distintas**, e essa separação é o principal
controle de segurança dele:

| Conexão | Usuário | Alcance |
| --- | --- | --- |
| JDBC direto | `USR_REPORTS` | leitura/escrita **só** no schema `SAP_REPORTS` (templates) |
| HTTP → `sap-odbc` | `API_LEITURA` | dado de negócio, somente leitura, SQL validado |

O `sap-reports` **nunca** lê dado de negócio por JDBC. Quem faz isso é o
`sap-odbc`, que valida a consulta (sem escrita, sem `SYS`, allowlist de tabelas)
antes de executar.

Consequência prática: comprometer o `sap-reports` não dá acesso ao SAP B1.

## Criar

Rode **[setup-usuario-reports.sql](setup-usuario-reports.sql)** como `SYSTEM` ou
`B1ADMIN`. Ele cria o usuário, o schema com esse usuário como dono, e traz a
consulta de conferência.

O Liquibase cria as tabelas (`RELATORIO`, `RELATORIO_VERSAO`,
`RELATORIO_AUDITORIA`) na primeira subida do serviço — você não precisa criá-las.
Ele também cria as próprias `DATABASECHANGELOG` e `DATABASECHANGELOGLOCK` no
mesmo schema, o que a propriedade de dono já permite.

## Usuário padrão, não *restricted*

O script usa `CREATE USER`, e não `CREATE RESTRICTED USER`.

O usuário restrito criado para o `sap-odbc` travou neste ambiente com
`[663] user not allowed to connect from client`, mesmo com
`RESTRICTED_USER_JDBC_ACCESS` **e** `RESTRICTED_USER_ODBC_ACCESS` concedidos — e a
causa nunca foi identificada. Usuário padrão não depende desses papéis de
interface.

O custo é receber o papel `PUBLIC` (leitura em várias views de `SYS`). Aqui isso
pesa menos do que pesaria no `sap-odbc`: este usuário **não tem privilégio nenhum
sobre dado de negócio**, então o `PUBLIC` não amplia o alcance dele sobre o ERP.

## Configurar

```bash
export REPORTS_JDBC_URL="jdbc:sap://SEU-HOST:30015/?validateCertificate=false"
export REPORTS_DB_USER="USR_REPORTS"
export REPORTS_DB_PASSWORD="..."
export REPORTS_DB_SCHEMA="SAP_REPORTS"

# Acesso ao dado de negócio — via gateway, nunca JDBC direto
export ODBC_BASE_URL="http://sap-odbc:8080"
export ODBC_API_KEY="..."
```

Para desenvolvimento local, copie `application-local.yaml.example` para
`application-local.yaml` (ignorado pelo git) e preencha lá.

## Verificar o isolamento

Conecte **como `USR_REPORTS`** e rode as duas consultas:

```sql
SELECT * FROM SAP_REPORTS.RELATORIO;      -- deve funcionar
SELECT * FROM SBOGRUPOROVEMA.OINV;        -- deve falhar: [258] insufficient privilege
```

A segunda é o teste que importa. Se ela funcionar, o isolamento não existe —
e os relatórios passariam a poder ler dado de negócio **sem** passar pela
validação do `sap-odbc`, contornando a allowlist de tabelas e o bloqueio de
escrita.

## Erros comuns

| Mensagem | Causa |
| --- | --- |
| `[258]: insufficient privilege` em `SAP_REPORTS` | o usuário não é dono do schema |
| `[10]: authentication failed` | senha errada, ou usuário travado após 6 tentativas |
| `[414]: user is forced to change password` | criado sem `NO FORCE_FIRST_PASSWORD_CHANGE` |
| `[332]: password has expired` | faltou `DISABLE PASSWORD LIFETIME` |
| `[663]: user not allowed to connect from client` | foi criado como *restricted* — use `CREATE USER` |
| Liquibase falha ao criar tabela | schema com outro dono: `ALTER SCHEMA ... OWNED BY USR_REPORTS` |
