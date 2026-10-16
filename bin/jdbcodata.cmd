@echo off
rem Starts JdbcOdata from the distribution directory (Windows).
rem Configuration: config\application.yml or environment variables (JDBCODATA_*).
setlocal
cd /d "%~dp0.."
if not exist data mkdir data
if not exist logs mkdir logs
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java"
"%JAVA_BIN%" -version >nul 2>&1
if errorlevel 1 (
  echo Java 21 or newer is required ^(set JAVA_HOME or put java on the PATH^)
  exit /b 1
)
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms256m -Xmx1g"
"%JAVA_BIN%" %JAVA_OPTS% -Dloader.path=lib -Dspring.config.additional-location=optional:file:./config/ -jar jdbcodata.jar %*
endlocal
