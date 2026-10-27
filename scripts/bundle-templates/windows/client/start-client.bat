@echo off
rem ==========================================================================
rem Arbitrator Participant - one-click client start (Windows 10/11, x64)
rem Set server.host in server.properties to the instructor PC's address first.
rem
rem Control flow uses goto, never ( ) blocks, and every path is quoted, so
rem folder names containing spaces, ")" or "&" work.
rem ==========================================================================
setlocal EnableExtensions DisableDelayedExpansion
title Arbitrator Client
rem pushd, unlike cd, also works when the folder is on a network share.
pushd "%~dp0"
set "HERE=%~dp0"
if not exist "%HERE%logs" mkdir "%HERE%logs" >nul 2>&1

if exist "%HERE%arbitrator-client-win.jar" goto have_jar
echo [ERROR] arbitrator-client-win.jar is not next to this script.
echo         If you opened this folder inside a ZIP file, close it, right-click the
echo         ZIP, choose Extract All, and run start-client.bat from the extracted folder.
goto fail
:have_jar

set "JAVA=%HERE%jre\bin\java.exe"
if exist "%JAVA%" goto have_java
echo [ERROR] The bundled Java runtime is missing: jre\bin\java.exe
echo         Copy the complete client folder again, or extract the ZIP again.
goto fail
:have_java

rem Files extracted from a downloaded ZIP carry a "downloaded from the
rem internet" mark that can make Windows block java.exe. Remove it once.
if exist "%HERE%logs\.unblocked" goto unblocked
echo [INFO] Unblocking downloaded files, first run only...
set "ARB_DIR=%HERE%"
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath $env:ARB_DIR -Recurse -File | Unblock-File" >nul 2>&1
echo done>"%HERE%logs\.unblocked"
:unblocked

"%JAVA%" -version >nul 2>&1
if not errorlevel 1 goto java_ok
echo [ERROR] The bundled Java runtime does not start.
echo         Antivirus software may have quarantined jre\bin\java.exe, or the copy is
echo         incomplete. Copy the client folder again and allow java.exe if asked.
goto fail
:java_ok

if exist "%HERE%server.properties" goto have_props
echo [ERROR] server.properties is missing. It must contain the instructor PC's address:
echo         server.host=192.168.x.x
goto fail
:have_props
findstr /r /c:"^server.host=127.0.0.1" /c:"^server.host=localhost" "%HERE%server.properties" >nul
if errorlevel 1 goto props_ok
echo [WARN] server.properties points at this PC itself (127.0.0.1). On a participant PC,
echo        set server.host to the instructor PC's LAN address first.
:props_ok

echo Starting Arbitrator Client...
"%JAVA%" -jar "%HERE%arbitrator-client-win.jar" %* 2>"%HERE%logs\client-errors.log"
if errorlevel 1 goto client_failed
endlocal
exit /b 0

:client_failed
echo [ERROR] The client stopped with an error. Details:
type "%HERE%logs\client-errors.log"
goto fail

:fail
echo.
echo This window stays open so you can read the message above.
pause
endlocal
exit /b 1
