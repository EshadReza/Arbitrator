@echo off
rem ==========================================================================
rem Arbitrator Participant - one-click client start (Windows)
rem Edit server.properties (server.host) to point at the instructor PC first.
rem ==========================================================================
setlocal
title Arbitrator Client
cd /d "%~dp0"

set "JAVA_CMD=%~dp0jre\bin\java.exe"
if exist "%JAVA_CMD%" goto have_java
set "JAVA_CMD=java"
where java >nul 2>&1
if errorlevel 1 goto no_java
:have_java

if not exist "%~dp0arbitrator-client-win.jar" goto no_jar

echo Starting Arbitrator Client...
"%JAVA_CMD%" -jar "%~dp0arbitrator-client-win.jar" %*
if errorlevel 1 pause
endlocal
exit /b 0

:no_java
echo [ERROR] No bundled JRE (jre\bin\java.exe) and no system java found. Install Java 17.
pause
exit /b 1

:no_jar
echo [ERROR] arbitrator-client-win.jar not found in %~dp0
pause
exit /b 1
