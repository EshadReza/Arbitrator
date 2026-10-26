@echo off
rem ==========================================================================
rem Arbitrator Admin - one-click server start (Windows)
rem Runs setup.bat (Docker Desktop, judge image, MySQL), then launches the server.
rem ==========================================================================
setlocal
title Arbitrator Server
cd /d "%~dp0"

if not defined DB_USER set "DB_USER=arbitrator"
if not defined DB_PASS set "DB_PASS=changeme"

set "ARBITRATOR_FROM_START_SCRIPT=1"
if not exist "%~dp0setup.bat" goto find_java
call "%~dp0setup.bat"
if errorlevel 1 exit /b 1

:find_java
set "JAVA_CMD=%~dp0jre\bin\java.exe"
if exist "%JAVA_CMD%" goto have_java
set "JAVA_CMD=java"
where java >nul 2>&1
if errorlevel 1 goto no_java
:have_java

set "JAR_FILE="
for %%f in ("%~dp0arbitrator-server*.jar") do if not defined JAR_FILE set "JAR_FILE=%%~ff"
if not defined JAR_FILE goto no_jar

echo.
echo ====================================================
echo   Launching Arbitrator Server...
echo   Instructor Console: http://localhost:8080/admin
echo   Close this window or press Ctrl+C to stop it.
echo ====================================================
echo.
"%JAVA_CMD%" -jar "%JAR_FILE%" --spring.datasource.username="%DB_USER%" --spring.datasource.password="%DB_PASS%" %*
if errorlevel 1 pause
endlocal
exit /b 0

:no_java
echo [ERROR] No bundled JRE (jre\bin\java.exe) and no system java found. Install Java 17.
pause
exit /b 1

:no_jar
echo [ERROR] Server JAR not found in %~dp0
pause
exit /b 1
