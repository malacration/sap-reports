-- ============================================================
-- Usuario e schema do sap-reports  -  rode como SYSTEM ou B1ADMIN
--
-- Este usuario guarda APENAS os templates de relatorio. Ele NAO le dado de
-- negocio: isso passa obrigatoriamente pelo sap-odbc, que valida o SQL e usa
-- um usuario somente-leitura proprio.
--
-- A separacao e o ponto central: comprometer o sap-reports nao da acesso ao
-- SAP Business One.
--
-- Ajuste o nome do schema/usuario e a senha antes de executar.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Diagnostico: o que ja existe?
-- ------------------------------------------------------------
SELECT 'USUARIO' AS TIPO, USER_NAME AS NOME FROM SYS.USERS WHERE USER_NAME = 'USR_REPORTS'
UNION ALL
SELECT 'SCHEMA', SCHEMA_NAME FROM SYS.SCHEMAS WHERE SCHEMA_NAME = 'SAP_REPORTS';

-- ------------------------------------------------------------
-- 2. O usuario
--
--    CREATE USER (padrao), NAO "CREATE RESTRICTED USER".
--
--    Motivo pratico: o usuario restrito criado para o sap-odbc travou neste
--    ambiente com [663] "user not allowed to connect from client", mesmo com
--    RESTRICTED_USER_JDBC_ACCESS e RESTRICTED_USER_ODBC_ACCESS concedidos, e a
--    causa nunca foi identificada. Usuario padrao nao depende desses papeis de
--    interface.
--
--    O custo e receber o papel PUBLIC (leitura em varias views de SYS). Aqui
--    isso pesa menos que no sap-odbc: este usuario nao tem privilegio nenhum
--    sobre dado de negocio, entao PUBLIC nao amplia o alcance sobre o ERP.
--
--    NO FORCE_FIRST_PASSWORD_CHANGE e obrigatorio em conta de servico: sem
--    isso o primeiro login exige troca interativa e a aplicacao nao sobe.
-- ------------------------------------------------------------
CREATE USER USR_REPORTS
    PASSWORD "TrocarEsta1Senha"
    NO FORCE_FIRST_PASSWORD_CHANGE;

-- Senha de servico nao pode expirar sozinha (padrao do HANA: 182 dias),
-- senao o servico cai sem aviso. A rotacao passa a ser responsabilidade sua.
ALTER USER USR_REPORTS DISABLE PASSWORD LIFETIME;

-- ------------------------------------------------------------
-- 3. O schema, com o usuario como DONO
--
--    Ser dono ja concede tudo o que o Liquibase precisa (criar tabela, indice,
--    chave estrangeira) sem espalhar GRANTs avulsos. E o privilegio fica
--    limitado a ESTE schema.
-- ------------------------------------------------------------
CREATE SCHEMA SAP_REPORTS OWNED BY USR_REPORTS;

-- ------------------------------------------------------------
-- 4. Conferencia - o que ele PODE
--    Espera-se apenas privilegio sobre SAP_REPORTS.
-- ------------------------------------------------------------
SELECT PRIVILEGE, OBJECT_TYPE, SCHEMA_NAME, OBJECT_NAME
FROM   SYS.EFFECTIVE_PRIVILEGES
WHERE  USER_NAME = 'USR_REPORTS'
   AND SCHEMA_NAME IS NOT NULL
ORDER  BY SCHEMA_NAME, OBJECT_NAME;

-- ============================================================
-- IMPORTANTE - o que NAO deve ser concedido
--
-- NAO conceda SELECT no schema de negocio (ex.: SBOGRUPOROVEMA).
-- O sap-reports le dado de negocio APENAS pelo sap-odbc, que valida a consulta
-- e roda com o usuario somente-leitura dele. Dar acesso direto aqui contorna
-- essa validacao inteira - a allowlist de tabelas, o bloqueio de escrita e o
-- de schema de sistema deixariam de valer para os relatorios.
-- ============================================================

-- ------------------------------------------------------------
-- 5. PROVA de isolamento - conecte COMO USR_REPORTS e rode:
-- ------------------------------------------------------------
-- Deve FUNCIONAR (o Liquibase cria as tabelas na primeira subida):
--   SELECT * FROM SAP_REPORTS.RELATORIO;
--
-- Deve FALHAR com [258]: insufficient privilege - e esse e o teste que importa:
--   SELECT * FROM SBOGRUPOROVEMA.OINV;
--
-- Se a segunda consulta FUNCIONAR, o isolamento nao existe: revise os grants
-- antes de subir o servico.

-- ============================================================
-- Se o usuario JA EXISTIR, nao recrie - conserte:
-- ============================================================
-- ALTER USER USR_REPORTS RESET CONNECT ATTEMPTS;   -- destrava apos 6 falhas
-- ALTER USER USR_REPORTS ACTIVATE;
-- ALTER USER USR_REPORTS PASSWORD "NovaSenha1Forte" NO FORCE_FIRST_PASSWORD_CHANGE;
-- ALTER USER USR_REPORTS DISABLE PASSWORD LIFETIME;
-- Schema ja existente com outro dono:
-- ALTER SCHEMA SAP_REPORTS OWNED BY USR_REPORTS;
