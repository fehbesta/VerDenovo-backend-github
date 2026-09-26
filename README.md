# VerDenovo Backend - Spring Boot

Backend da aplicação VerDenovo desenvolvido em Spring Boot com SQL Server.

## Configuração
1. Crie um banco SQL Server vazio (ou use um host como Somee/Azure)
2. Copie `.env.example` para `.env` e preencha com suas próprias credenciais
   (NUNCA commite o `.env` — o `.gitignore` já bloqueia isso)
3. Execute: `mvn spring-boot:run`

O schema das tabelas é criado/atualizado automaticamente pelo Hibernate
(`spring.jpa.hibernate.ddl-auto=update`). Os arquivos `database.sql` e
`migration.sql` são apenas referência/scripts de apoio — veja os comentários
em cada um para saber quando (e se) você precisa rodá-los manualmente.

## Endpoints

### Autenticação
- POST `/api/auth/login` - Login
- POST `/api/auth/cadastro` - Cadastro

### Pontos de Coleta
- GET `/api/pontos` - Listar pontos
- POST `/api/pontos` - Criar ponto
- DELETE `/api/pontos/{id}` - Remover ponto

### Categorias
- GET `/api/categorias` - Listar categorias

## Banco de Dados
As credenciais de conexão vêm exclusivamente de variáveis de ambiente
(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) — veja `.env.example`.

⚠️ Este arquivo continha anteriormente credenciais reais de um banco SQL
Server hospedado no Somee, em texto puro, em um repositório público.
Se essa senha ainda estiver em uso, **troque-a imediatamente**.

## Usuário Admin Padrão
Definido pelas variáveis de ambiente `ADMIN_EMAIL` e `ADMIN_SENHA` (não fica
fixo no código).