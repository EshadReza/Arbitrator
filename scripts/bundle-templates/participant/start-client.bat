@echo off
title Arbitrator Client
echo Starting Arbitrator Client...
cd /d "%~dp0"

set "JAVA_CMD=java"
if exist ".\jre\bin\java.exe" (
    set "JAVA_CMD=.\jre\bin\java.exe"
)

if exist "arbitrator-client-linux.jar" (
    "%JAVA_CMD%" -jar "arbitrator-client-linux.jar" %*
    goto :done
)

for %%f in (arbitrator-client*.jar) do (
    "%JAVA_CMD%" -jar "%%f" %*
    goto :done
)

echo Error: Client JAR not found.
pause

:done
