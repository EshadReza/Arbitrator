@echo off
rem ==========================================================================
rem Arbitrator Admin - one-click server start (Windows 10/11, x64)
rem
rem  1. Checks the folder was extracted and unblocks downloaded files
rem  2. Finds or installs Docker Desktop (winget), starts it, and switches it
rem     to Linux containers
rem  3. Loads or builds the judge sandbox image (arbitrator-judge:latest)
rem  4. Loads or pulls mysql:8.0 and runs the bundle's own arbitrator-mysql
rem     container on 127.0.0.1 (3306, or the next free port), then checks the
rem     database login
rem  5. Opens port 8080 in Windows Firewall and starts the server with the
rem     bundled Java
rem
rem Every step is skipped when it is already done, so re-running is safe.
rem Override defaults with DB_USER, DB_PASS, ARBITRATOR_DOCKER_IMAGE and
rem ARBITRATOR_DOCKER_PLATFORM environment variables. Arguments are passed to
rem the server.
rem
rem Written for cmd.exe on purpose:
rem  - Control flow uses goto, never ( ) blocks: cmd expands %ERRORLEVEL% and
rem    paths inside a block once, when the block is parsed, and a folder name
rem    containing ")" or "&" breaks a block.
rem  - Every path is quoted, and messages avoid & | < > ^ characters.
rem ==========================================================================
setlocal EnableExtensions DisableDelayedExpansion
title Arbitrator Server
rem pushd, unlike cd, also works when the folder is on a network share (\\server\share).
pushd "%~dp0"
set "HERE=%~dp0"

if not defined DB_USER set "DB_USER=arbitrator"
if not defined DB_PASS set "DB_PASS=changeme"
if not defined ARBITRATOR_DOCKER_IMAGE set "ARBITRATOR_DOCKER_IMAGE=arbitrator-judge:latest"
if not defined ARBITRATOR_DOCKER_PLATFORM set "ARBITRATOR_DOCKER_PLATFORM=linux/amd64"
set "MYSQL_CONTAINER=arbitrator-mysql"
set "SERVER_PORT=8080"
set "TMPF=%TEMP%\arbitrator-start-%RANDOM%.txt"

if not exist "%HERE%logs" mkdir "%HERE%logs" >nul 2>&1
set "LOG=%HERE%logs\start-server.log"
echo ===== start-server.bat %DATE% %TIME% ===== >>"%LOG%" 2>nul

echo ====================================================
echo    Arbitrator Server - Windows
echo ====================================================

rem --------------------------------------------------------------------------
rem STEP 1: Bundle sanity
rem --------------------------------------------------------------------------
rem The pattern is relative to the current folder (pushd above) and unquoted:
rem a quoted wildcard with a full path is not expanded by every cmd variant.
set "JAR="
for %%f in (arbitrator-server*.jar) do if not defined JAR set "JAR=%%~ff"
if defined JAR goto have_jar
call :say [ERROR] The server JAR is not next to this script.
call :say         If you opened this folder inside a ZIP file, close it, right-click the
call :say         ZIP, choose Extract All, and run start-server.bat from the extracted folder.
goto fail
:have_jar

set "JAVA=%HERE%jre\bin\java.exe"
if exist "%JAVA%" goto have_bundled_java
call :say [ERROR] The bundled Java runtime is missing: jre\bin\java.exe
call :say         Copy the complete admin folder again, or extract the ZIP again.
goto fail
:have_bundled_java

rem Files extracted from a downloaded ZIP carry a "downloaded from the
rem internet" mark that can make Windows block java.exe. Remove it once.
if exist "%HERE%logs\.unblocked" goto unblocked
call :say [INFO] Unblocking downloaded files, first run only...
set "ARB_DIR=%HERE%"
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath $env:ARB_DIR -Recurse -File | Unblock-File" >nul 2>&1
echo done>"%HERE%logs\.unblocked"
:unblocked

"%JAVA%" -version >nul 2>&1
if not errorlevel 1 goto java_ok
call :say [ERROR] The bundled Java runtime does not start.
call :say         Antivirus software may have quarantined jre\bin\java.exe, or the copy is
call :say         incomplete. Copy the admin folder again and allow java.exe if asked.
goto fail
:java_ok
call :say [OK] Bundled Java works.

rem --------------------------------------------------------------------------
rem STEP 2: Docker Desktop
rem --------------------------------------------------------------------------
call :say [INFO] Step 2/5: Checking Docker Desktop...
rem Running the program is the reliable "is it installed" test; it fails with
rem errorlevel 9009 when docker is not on PATH.
docker --version >nul 2>&1
if not errorlevel 1 goto docker_on_path
rem Installed but not on this window's PATH yet, for example right after installing.
if exist "%ProgramFiles%\Docker\Docker\resources\bin\docker.exe" set "PATH=%ProgramFiles%\Docker\Docker\resources\bin;%PATH%"
docker --version >nul 2>&1
if not errorlevel 1 goto docker_on_path

call :say [WARN] Docker Desktop is not installed.
winget --version >nul 2>&1
if errorlevel 1 goto docker_manual_install
call :say [INFO] Installing Docker Desktop with winget. Windows will ask for administrator approval.
winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
if errorlevel 1 goto docker_manual_install
call :say [OK] Docker Desktop was installed.
call :say [INFO] Restart Windows now. After the restart, start Docker Desktop once, accept its
call :say        terms, then run start-server.bat again.
goto fail

:docker_manual_install
call :say [ERROR] Docker Desktop could not be installed automatically.
call :say         Install it from https://www.docker.com/products/docker-desktop/
call :say         as an administrator, restart Windows, then run start-server.bat again.
goto fail

:docker_on_path
docker info >nul 2>&1
if not errorlevel 1 goto docker_running
call :say [INFO] Docker engine is not running. Starting Docker Desktop...
if exist "%ProgramFiles%\Docker\Docker\Docker Desktop.exe" start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
set /a WAITED=0
:docker_wait
docker info >nul 2>&1
if not errorlevel 1 goto docker_running
if %WAITED% geq 300 goto docker_timeout
rem ping is used as a sleep because timeout.exe fails when input is redirected.
ping -n 6 127.0.0.1 >nul
set /a WAITED+=5
goto docker_wait

:docker_timeout
call :say [ERROR] The Docker engine did not start within 5 minutes. Common causes:
call :say         - Docker Desktop is waiting for its terms to be accepted: open it and accept.
call :say         - Virtualization or WSL 2 is not enabled: Docker Desktop shows how to fix it.
call :say         - This account is not in the docker-users group: an administrator must run
call :say           net localgroup docker-users YOUR-USERNAME /add   then sign out and in.
goto fail

:docker_running
rem The judge and MySQL images are Linux images; Windows-containers mode cannot run them.
docker info --format "{{.OSType}}" >"%TMPF%" 2>nul
set "OSTYPE="
set /p OSTYPE=<"%TMPF%"
if /i "%OSTYPE%"=="linux" goto docker_linux
call :say [INFO] Docker is in Windows-containers mode. Switching to Linux containers...
if exist "%ProgramFiles%\Docker\Docker\DockerCli.exe" "%ProgramFiles%\Docker\Docker\DockerCli.exe" -SwitchLinuxEngine
set /a WAITED=0
:docker_linux_wait
ping -n 6 127.0.0.1 >nul
set /a WAITED+=5
docker info --format "{{.OSType}}" >"%TMPF%" 2>nul
set "OSTYPE="
set /p OSTYPE=<"%TMPF%"
if /i "%OSTYPE%"=="linux" goto docker_linux
if %WAITED% lss 120 goto docker_linux_wait
call :say [ERROR] Docker did not switch to Linux containers. Right-click the Docker icon
call :say         in the taskbar, choose Switch to Linux containers, then run this again.
goto fail

:docker_linux
call :say [OK] Docker engine is running with Linux containers.

rem --------------------------------------------------------------------------
rem STEP 3: Judge sandbox image
rem --------------------------------------------------------------------------
call :say [INFO] Step 3/5: Checking judge sandbox image...
call :ensure_image "%ARBITRATOR_DOCKER_IMAGE%" arbitrator-judge.tar.gz nopull
if not errorlevel 1 goto judge_ready
if exist "%HERE%docker\Dockerfile" goto judge_build
call :say [ERROR] docker\Dockerfile is missing from this bundle; cannot build the judge image.
goto fail

:judge_build
call :say [INFO] Building the judge image; the first build downloads Ubuntu and compilers...
set /a TRIES=0
:judge_build_retry
set /a TRIES+=1
docker buildx build --platform "%ARBITRATOR_DOCKER_PLATFORM%" -t "%ARBITRATOR_DOCKER_IMAGE%" --load "%HERE%docker"
if not errorlevel 1 goto judge_ready
if %TRIES% geq 3 goto judge_failed
call :say [WARN] Build attempt %TRIES% of 3 failed; retrying in 15 seconds...
ping -n 16 127.0.0.1 >nul
goto judge_build_retry

:judge_failed
call :say [ERROR] Could not build the judge image. Check the internet connection, or use a
call :say         bundle built with --with-images, which needs no download.
goto fail

:judge_ready
call :say [OK] Judge sandbox image is ready.

rem --------------------------------------------------------------------------
rem STEP 4: MySQL 8 container
rem --------------------------------------------------------------------------
call :say [INFO] Step 4/5: Checking the MySQL 8 database container...
docker container inspect "%MYSQL_CONTAINER%" >nul 2>&1
if errorlevel 1 goto mysql_create
docker container inspect -f "{{.State.Running}}" "%MYSQL_CONTAINER%" >"%TMPF%" 2>nul
set "RUNNING="
set /p RUNNING=<"%TMPF%"
if /i "%RUNNING%"=="true" goto mysql_port
call :say [INFO] Starting the existing MySQL container...
docker start "%MYSQL_CONTAINER%" >nul 2>&1
if not errorlevel 1 goto mysql_port
rem Usually its old port is taken by another program now. The data lives in
rem the arbitrator-mysql-data volume, so re-creating the container keeps it.
call :say [WARN] The existing container could not start; re-creating it. Stored data is kept.
docker rm -f "%MYSQL_CONTAINER%" >nul 2>&1

:mysql_create
call :ensure_image mysql:8.0 mysql-8.0.tar.gz pull
if errorlevel 1 goto fail
set /a MYSQL_PORT=3306
:mysql_create_try
call :port_in_use %MYSQL_PORT%
if not errorlevel 1 goto mysql_next_port
call :say [INFO] Creating MySQL 8 container on 127.0.0.1:%MYSQL_PORT%...
docker run -d --name "%MYSQL_CONTAINER%" --restart unless-stopped -p "127.0.0.1:%MYSQL_PORT%:3306" -e MYSQL_DATABASE=arbitrator -e "MYSQL_USER=%DB_USER%" -e "MYSQL_PASSWORD=%DB_PASS%" -e "MYSQL_ROOT_PASSWORD=%DB_PASS%" -v arbitrator-mysql-data:/var/lib/mysql mysql:8.0 >nul 2>"%TMPF%"
if not errorlevel 1 goto mysql_port
rem The port check can miss a port that is reserved but not listening;
rem docker refuses it, so remove the half-created container and move on.
docker rm -f "%MYSQL_CONTAINER%" >nul 2>&1
:mysql_next_port
if %MYSQL_PORT% neq 3306 goto mysql_next_port_inc
call :say [WARN] Port 3306 is used by another program, probably a MySQL installed on this PC.
:mysql_next_port_inc
set /a MYSQL_PORT+=1
if %MYSQL_PORT% leq 3330 goto mysql_create_try
call :say [ERROR] Could not start MySQL on any port from 3306 to 3330. Docker said:
type "%TMPF%"
goto fail

:mysql_port
docker inspect -f "{{range $k, $v := .HostConfig.PortBindings}}{{range $v}}{{.HostPort}}{{end}}{{end}}" "%MYSQL_CONTAINER%" >"%TMPF%" 2>nul
set "MYSQL_PORT="
set /p MYSQL_PORT=<"%TMPF%"
if defined MYSQL_PORT goto mysql_wait_init
call :say [ERROR] The MySQL container publishes no port. Remove it with
call :say         docker rm -f arbitrator-mysql   -- the data is kept -- and run this again.
goto fail

:mysql_wait_init
call :say [OK] MySQL is published on 127.0.0.1:%MYSQL_PORT%.
call :say [INFO] Waiting for MySQL to accept connections; the first start takes a minute...
set /a WAITED=0
:mysql_wait
rem TCP ping only succeeds once the final server is up, not the temporary
rem socket-only server MySQL runs while initializing.
docker exec -e "MYSQL_PWD=%DB_PASS%" "%MYSQL_CONTAINER%" mysqladmin ping -h 127.0.0.1 --protocol=tcp -uroot --silent >nul 2>&1
if not errorlevel 1 goto mysql_ready
if %WAITED% geq 180 goto mysql_timeout
ping -n 3 127.0.0.1 >nul
set /a WAITED+=2
goto mysql_wait

:mysql_timeout
call :say [ERROR] MySQL did not become ready within 3 minutes. Its log:
docker logs --tail 30 "%MYSQL_CONTAINER%"
goto fail

:mysql_ready
call :say [OK] MySQL is accepting connections.
docker exec -e "MYSQL_PWD=%DB_PASS%" "%MYSQL_CONTAINER%" mysql -h 127.0.0.1 --protocol=tcp -uroot -e "CREATE DATABASE IF NOT EXISTS arbitrator CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '%DB_USER%'@'%%'; ALTER USER '%DB_USER%'@'%%' IDENTIFIED BY '%DB_PASS%'; GRANT ALL PRIVILEGES ON arbitrator.* TO '%DB_USER%'@'%%'; CREATE USER IF NOT EXISTS '%DB_USER%'@'localhost'; ALTER USER '%DB_USER%'@'localhost' IDENTIFIED BY '%DB_PASS%'; GRANT ALL PRIVILEGES ON arbitrator.* TO '%DB_USER%'@'localhost'; FLUSH PRIVILEGES;"
if not errorlevel 1 goto mysql_synced
call :say [ERROR] Could not log in to MySQL as root. The container was probably created with
call :say         a different DB_PASS. Run again with that DB_PASS set.
call :say         Removing the arbitrator-mysql-data volume resets it but DELETES all contest data.
goto fail

:mysql_synced
rem Same TCP path and credentials the server uses, so a mismatch is reported
rem here in plain words instead of as a Java stack trace.
docker exec -e "MYSQL_PWD=%DB_PASS%" "%MYSQL_CONTAINER%" mysql -h 127.0.0.1 --protocol=tcp "-u%DB_USER%" -e "SELECT 1" arbitrator >nul 2>&1
if not errorlevel 1 goto db_login_ok
call :say [ERROR] User %DB_USER% cannot log in to the arbitrator database.
goto fail
:db_login_ok
call :say [OK] Database login as %DB_USER% works.

rem --------------------------------------------------------------------------
rem STEP 5: Firewall and server
rem --------------------------------------------------------------------------
call :say [INFO] Step 5/5: Starting the server...
call :port_in_use %SERVER_PORT%
if errorlevel 1 goto server_port_free
call :say [ERROR] Port %SERVER_PORT% is already in use, probably by an Arbitrator server that is
call :say         still running in another window. Close that window, then run this again.
goto fail
:server_port_free

rem Participants connect on port 8080; Windows Firewall blocks it by default.
netsh advfirewall firewall show rule name="Arbitrator Server 8080" >nul 2>&1
if not errorlevel 1 goto firewall_ok
call :say [INFO] Allowing participants through Windows Firewall on port 8080.
call :say        Windows will ask for administrator approval once.
powershell -NoProfile -NonInteractive -Command "try { Start-Process -FilePath netsh -ArgumentList 'advfirewall firewall add rule name=\"Arbitrator Server 8080\" dir=in action=allow protocol=TCP localport=8080' -Verb RunAs -Wait -WindowStyle Hidden } catch { exit 1 }" >nul 2>&1
netsh advfirewall firewall show rule name="Arbitrator Server 8080" >nul 2>&1
if not errorlevel 1 goto firewall_ok
call :say [WARN] The firewall rule was not added. Participants may not be able to connect.
call :say        If Windows asks whether to allow Java, choose Allow on private networks.
:firewall_ok

echo.
call :say ====================================================
call :say   Instructor console: http://localhost:%SERVER_PORT%/admin
call :say   Keep this window open while the contest runs.
call :say   Server log: logs\server.log
call :say ====================================================
echo.
"%JAVA%" -jar "%JAR%" "--spring.datasource.url=jdbc:mysql://127.0.0.1:%MYSQL_PORT%/arbitrator?serverTimezone=UTC&createDatabaseIfNotExist=true" "--spring.datasource.username=%DB_USER%" "--spring.datasource.password=%DB_PASS%" "--logging.file.name=%HERE%logs\server.log" %*
if errorlevel 1 goto server_failed
call :say [INFO] The server stopped.
del "%TMPF%" >nul 2>&1
pause
endlocal
exit /b 0

:server_failed
call :say [ERROR] The server failed to start or crashed. The cause is the last Caused by
call :say         line above; the full log is in logs\server.log
goto fail

rem ==========================================================================
rem Subroutines
rem ==========================================================================

:say
rem Prints a message and appends it to logs\start-server.log.
echo %*
>>"%LOG%" echo %* 2>nul
exit /b 0

:port_in_use
rem errorlevel 0 when something listens on TCP port %1, else 1.
powershell -NoProfile -NonInteractive -Command "if (Get-NetTCPConnection -State Listen -LocalPort %1 -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 2 goto port_in_use_netstat
exit /b %ERRORLEVEL%
:port_in_use_netstat
netstat -an | findstr /r /c:":%1 .*LISTEN" >nul
exit /b %ERRORLEVEL%

:ensure_image
rem %1 image, %2 file in images\, %3 pull or nopull. errorlevel 0 when present.
docker image inspect "%~1" >nul 2>&1
if not errorlevel 1 exit /b 0
if not exist "%HERE%images\%~2" goto ensure_image_pull
call :say [INFO] Loading %~1 from the bundle, no download needed...
docker load -i "%HERE%images\%~2"
docker image inspect "%~1" >nul 2>&1
if not errorlevel 1 exit /b 0
call :say [WARN] images\%~2 did not contain %~1.
:ensure_image_pull
if /i "%~3"=="nopull" exit /b 1
set /a PULLS=0
:ensure_image_pull_retry
set /a PULLS+=1
call :say [INFO] Downloading %~1, attempt %PULLS% of 5...
docker pull "%~1"
if not errorlevel 1 exit /b 0
if %PULLS% geq 5 goto ensure_image_pull_failed
ping -n 11 127.0.0.1 >nul
goto ensure_image_pull_retry
:ensure_image_pull_failed
call :say [ERROR] Could not download %~1. Check the internet connection, or use a bundle
call :say         built with --with-images, which needs no download.
exit /b 1

:fail
echo.
call :say [ERROR] Arbitrator did not start. This window stays open so you can read why.
call :say         A copy of these messages is in logs\start-server.log
del "%TMPF%" >nul 2>&1
pause
endlocal
exit /b 1
