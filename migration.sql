-- ============================================================================
-- Script de migração para bancos JÁ EXISTENTES (SQL Server)
-- ============================================================================
-- Use este script SOMENTE se você já tinha um banco criado a partir de uma
-- versão antiga do database.sql (com colunas em camelCase, ex: nivelAcesso,
-- dataCadastro, statusPonto, horaFuncionamento).
--
-- O Hibernate (JPA) usa por padrão nomes de coluna em snake_case (ex:
-- nivel_acesso, data_cadastro). Se o banco já existia com as colunas antigas
-- em camelCase, o Hibernate não as reconhecia e criava colunas novas e vazias
-- ao lado das antigas (com ddl-auto=update) — fazendo dados parecerem "não
-- salvos". Este script corrige isso RENOMEANDO as colunas antigas para o
-- nome que o Hibernate já espera, preservando todos os dados existentes.
--
-- Se o seu banco for novo (criado direto pela aplicação, com ddl-auto=update,
-- sem nunca ter rodado o database.sql antigo), você NÃO precisa rodar este
-- script — o schema já estará correto.
--
-- Este script é seguro para rodar mais de uma vez (idempotente).
-- ============================================================================

USE VerdNovo
GO

-- ---------------------------------------------------------------------------
-- Tabela Usuario: renomear colunas antigas (camelCase) para snake_case
-- ---------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'nivelAcesso')
   AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'nivel_acesso')
    EXEC sp_rename 'Usuario.nivelAcesso', 'nivel_acesso', 'COLUMN';
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'dataCadastro')
   AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'data_cadastro')
    EXEC sp_rename 'Usuario.dataCadastro', 'data_cadastro', 'COLUMN';
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'statusUsuario')
   AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'status_usuario')
    EXEC sp_rename 'Usuario.statusUsuario', 'status_usuario', 'COLUMN';
GO

-- Colunas de recuperação de senha que podem não existir em bancos bem antigos
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'reset_token')
    ALTER TABLE Usuario ADD reset_token VARCHAR(100) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'reset_token_expiry')
    ALTER TABLE Usuario ADD reset_token_expiry DATETIME2 NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Usuario' AND COLUMN_NAME = 'reset_code')
    ALTER TABLE Usuario ADD reset_code VARCHAR(6) NULL;
GO

-- ---------------------------------------------------------------------------
-- Tabela Categoria: renomear coluna antiga (camelCase) para snake_case
-- ---------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Categoria' AND COLUMN_NAME = 'statusCategoria')
   AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Categoria' AND COLUMN_NAME = 'status_categoria')
    EXEC sp_rename 'Categoria.statusCategoria', 'status_categoria', 'COLUMN';
GO

-- ---------------------------------------------------------------------------
-- Tabela Ponto: renomear colunas antigas (camelCase) para snake_case
-- ---------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'dataCadastro')
   AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'data_cadastro')
    EXEC sp_rename 'Ponto.dataCadastro', 'data_cadastro', 'COLUMN';
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'statusPonto')
   AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'status_ponto')
    EXEC sp_rename 'Ponto.statusPonto', 'status_ponto', 'COLUMN';
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'horaFuncionamento')
   AND NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'hora_funcionamento')
    EXEC sp_rename 'Ponto.horaFuncionamento', 'hora_funcionamento', 'COLUMN';
GO

-- Tornar senha nullable na tabela Ponto (pontos podem não ter login próprio)
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'senha')
    ALTER TABLE Ponto ALTER COLUMN senha VARCHAR(100) NULL;
GO

-- Colunas adicionadas em versões anteriores, mantidas por segurança/idempotência
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'descricao')
    ALTER TABLE Ponto ADD descricao VARCHAR(500) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'logradouro')
    ALTER TABLE Ponto ADD logradouro VARCHAR(100) NULL;
GO

-- *** CORREÇÃO PRINCIPAL ***
-- A tabela Ponto antiga exigia categoria_id (NOT NULL), mas a aplicação nunca
-- enviava esse valor (a entidade Java não tinha esse campo) — todo cadastro
-- de ponto de coleta falhava silenciosamente por violar essa restrição.
-- Agora a aplicação atribui uma categoria padrão automaticamente, mas por
-- segurança tornamos a coluna NULL-ável também no banco:
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'categoria_id')
    ALTER TABLE Ponto ALTER COLUMN categoria_id INT NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'categoria_id')
    ALTER TABLE Ponto ADD categoria_id INT NULL;
GO

-- Novas colunas usadas pelo app mobile para exibir os pontos no mapa
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'latitude')
    ALTER TABLE Ponto ADD latitude FLOAT NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Ponto' AND COLUMN_NAME = 'longitude')
    ALTER TABLE Ponto ADD longitude FLOAT NULL;
GO

-- Garante que existe ao menos uma categoria "Geral" para servir de padrão
-- (a aplicação também cria isso automaticamente no startup, via DataInitializer)
IF NOT EXISTS (SELECT 1 FROM Categoria WHERE nome = 'Geral')
    INSERT INTO Categoria (nome, descricao, status_categoria) VALUES
    ('Geral', 'Categoria padrão para pontos de coleta', 'ATIVO');
GO
