@echo off
echo Executando VerDenovo Backend...
echo.

java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERRO: Java nao encontrado!
    echo Instale Java 17 ou superior: https://adoptium.net/
    pause
    exit /b 1
)

if not exist .env (
    echo AVISO: Arquivo .env nao encontrado! Copie .env.example para .env e preencha os valores.
    pause
    exit /b 1
)

echo Carregando variaveis de ambiente do .env...
for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" if not "%%A:~0,1%%"=="#" set "%%A=%%B"
)

if exist mvnw.cmd (
    echo Usando Maven Wrapper...
    mvnw.cmd spring-boot:run -Dspring-boot.run.jvmArguments="-DDB_URL=%DB_URL% -DDB_USERNAME=%DB_USERNAME% -DDB_PASSWORD=%DB_PASSWORD% -DJWT_SECRET=%JWT_SECRET% -DADMIN_EMAIL=%ADMIN_EMAIL% -DADMIN_SENHA=%ADMIN_SENHA% -DCORS_ALLOWED_ORIGINS=%CORS_ALLOWED_ORIGINS% -Dspring.mail.username=%MAIL_USERNAME% -Dspring.mail.password=%MAIL_PASSWORD% -DFRONTEND_URL=%FRONTEND_URL%"
) else (
    mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DDB_URL=%DB_URL% -DDB_USERNAME=%DB_USERNAME% -DDB_PASSWORD=%DB_PASSWORD% -DJWT_SECRET=%JWT_SECRET% -DADMIN_EMAIL=%ADMIN_EMAIL% -DADMIN_SENHA=%ADMIN_SENHA% -DCORS_ALLOWED_ORIGINS=%CORS_ALLOWED_ORIGINS% -Dspring.mail.username=%MAIL_USERNAME% -Dspring.mail.password=%MAIL_PASSWORD% -DFRONTEND_URL=%FRONTEND_URL%"
)

pause
