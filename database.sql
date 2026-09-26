-- ============================================================================
-- Script de referência do schema do banco (SQL Server)
-- ============================================================================
-- IMPORTANTE: este script NÃO é necessário para rodar o projeto.
-- O backend usa `spring.jpa.hibernate.ddl-auto=update` (ver application.properties),
-- então o Hibernate cria/atualiza as tabelas automaticamente na primeira execução,
-- com base nas entidades Java em src/main/java/com/verdenovo/api/entity/.
--
-- Esse arquivo serve apenas como referência/documentação do schema esperado,
-- ou para quem preferir criar o banco manualmente antes de rodar a aplicação.
-- Os nomes de coluna abaixo usam snake_case porque é essa a convenção que o
-- Hibernate usa por padrão no Spring Boot (SpringPhysicalNamingStrategy
-- converte campos camelCase do Java, ex: nivelAcesso, para snake_case no
-- banco, ex: nivel_acesso). Se você editar este script, mantenha os nomes
-- de coluna em snake_case para não conflitar com o que o Hibernate espera.
-- ============================================================================

USE VerdNovo
GO

CREATE TABLE Usuario
(
   id                  INT            IDENTITY,
   nome                VARCHAR(100)   NOT NULL,
   email               VARCHAR(100)   UNIQUE NOT NULL,
   senha               VARCHAR(100)   NOT NULL,
   nivel_acesso        VARCHAR(10)    NULL, -- ADMIN ou USER
   data_cadastro       DATETIME2      NOT NULL,
   status_usuario      VARCHAR(20)    NOT NULL, -- ATIVO ou INATIVO
   reset_token         VARCHAR(100)   NULL,
   reset_token_expiry  DATETIME2      NULL,
   reset_code          VARCHAR(6)     NULL,

   PRIMARY KEY (id)
)

CREATE TABLE Categoria
(
	id                INT            IDENTITY,
	nome              VARCHAR(50)    NOT NULL,
	descricao         VARCHAR(200)   NOT NULL,
	status_categoria  VARCHAR(20)    NOT NULL, -- ATIVO ou INATIVO

	PRIMARY KEY (id)
)

CREATE TABLE Ponto
(
	id                  INT             IDENTITY,
	nome                VARCHAR(50)     NOT NULL,
	cep                 VARCHAR(8)      NOT NULL,
	numero              VARCHAR(10)     NOT NULL,
	complemento         VARCHAR(50)     NULL,
	telefone            VARCHAR(20)     NULL,
	email               VARCHAR(50)     NULL,
	hora_funcionamento  VARCHAR(200)    NOT NULL,
	material            VARCHAR(400)    NOT NULL,
	senha               VARCHAR(100)    NULL,
	data_cadastro       DATETIME2       NULL,
	status_ponto        VARCHAR(20)     NOT NULL, -- PENDENTE, ATIVO, INATIVO ou REJEITADO
	descricao           VARCHAR(500)    NULL,
	logradouro          VARCHAR(100)    NULL,
	usuario_id          INT             NULL,
	categoria_id        INT             NULL, -- nullable: a aplicação atribui uma categoria padrão automaticamente
	latitude            FLOAT           NULL, -- preenchido automaticamente via geocodificação do endereço
	longitude           FLOAT           NULL,

	PRIMARY KEY (id),
	FOREIGN KEY (categoria_id) REFERENCES Categoria (id),
	FOREIGN KEY (usuario_id) REFERENCES Usuario (id)
)

-- Dados iniciais (o DataInitializer.java também cria isso automaticamente
-- na primeira execução da aplicação, então só rode este INSERT se estiver
-- criando o banco manualmente e SEM deixar a aplicação rodar uma vez antes).
INSERT INTO Categoria (nome, descricao, status_categoria) VALUES
('Geral', 'Categoria padrão para pontos de coleta', 'ATIVO')
