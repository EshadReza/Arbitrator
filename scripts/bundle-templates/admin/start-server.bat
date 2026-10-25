@echo off
title Arbitrator Server
echo Starting Arbitrator Judge Server...
cd /d "%~dp0"

set "JAVA_CMD=java"
if exist ".\jre\bin\java.exe" (
    set "JAVA_CMD=.\jre\bin\java.exe"
)

if "%DB_USER%"=="" set "DB_USER=arbitrator"
if "%DB_PASS%"=="" set "DB_PASS=changeme"

if exist "arbitrator-server-0.1.0-SNAPSHOT.jar" (
    "%JAVA_CMD%" -jar "arbitrator-server-0.1.0-SNAPSHOT.jar" %*
    goto :done
)

for %%f in (arbitrator-server*.jar) do (
    "%JAVA_CMD%" -jar "%%f" %*
    goto :done
)

echo Error: Server JAR not found.
pause

:done
