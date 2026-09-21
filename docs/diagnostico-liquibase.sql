-- ============================================================
-- "Tabela nao existe" no sap-reports: o Liquibase rodou?
--
-- Rode como USR_REPORTS (ou SYSTEM). Cada passo elimina uma causa.
-- ============================================================

-- ------------------------------------------------------------
-- 1. O SCHEMA existe?
--
--    Esta e a causa mais comum. O changelog cria as TABELAS, mas NAO cria o
--    schema - o Liquibase falha se ele nao existir. Quem cria e o
--    setup-usuario-reports.sql.
-- ------------------------------------------------------------
SELECT SCHEMA_NAME, SCHEMA_OWNER FROM SYS.SCHEMAS WHERE SCHEMA_NAME = 'SAP_REPORTS';
-- Vazio  -> rode docs/setup-usuario-reports.sql e reinicie o servico.
-- Existe -> siga para o passo 2.

-- ------------------------------------------------------------
-- 2. O Liquibase chegou a registrar alguma coisa?
--
--    Ele cria estas duas tabelas de controle no proprio schema. Se elas nao
--    existem, ele nunca executou (ou nao conseguiu nem comecar).
-- ------------------------------------------------------------
SELECT TABLE_NAME FROM SYS.TABLES
WHERE  SCHEMA_NAME = 'SAP_REPORTS'
ORDER  BY TABLE_NAME;
-- Esperado: DATABASECHANGELOG, DATABASECHANGELOGLOCK, RELATORIO,
--           RELATORIO_AUDITORIA, RELATORIO_TOKEN, RELATORIO_VERSAO

-- ------------------------------------------------------------
-- 3. Quais changesets aplicaram?
-- ------------------------------------------------------------
SELECT ID, AUTHOR, FILENAME, DATEEXECUTED, EXECTYPE
FROM   SAP_REPORTS.DATABASECHANGELOG
ORDER  BY DATEEXECUTED;
-- EXECTYPE = EXECUTED  -> aplicado
-- EXECTYPE = FAILED    -> olhe o log da aplicacao para a causa

-- ------------------------------------------------------------
-- 4. Ha um LOCK preso?
--
--    Se a aplicacao morreu no meio de uma migracao, o lock fica travado e as
--    proximas subidas ficam esperando. Sintoma: o servico nao sobe e nao
--    reclama de nada especifico.
-- ------------------------------------------------------------
SELECT ID, LOCKED, LOCKGRANTED, LOCKEDBY FROM SAP_REPORTS.DATABASECHANGELOGLOCK;
-- LOCKED = TRUE de uma execucao antiga -> libere:
--   UPDATE SAP_REPORTS.DATABASECHANGELOGLOCK SET LOCKED = FALSE, LOCKGRANTED = NULL, LOCKEDBY = NULL;

-- ------------------------------------------------------------
-- 5. O usuario consegue criar tabela no schema?
--
--    Se o schema existe mas pertence a outro usuario, o Liquibase falha com
--    [258] insufficient privilege.
-- ------------------------------------------------------------
SELECT PRIVILEGE, OBJECT_TYPE, SCHEMA_NAME
FROM   SYS.EFFECTIVE_PRIVILEGES
WHERE  USER_NAME = 'USR_REPORTS' AND SCHEMA_NAME = 'SAP_REPORTS';
-- Sem privilegio -> ALTER SCHEMA SAP_REPORTS OWNED BY USR_REPORTS;
