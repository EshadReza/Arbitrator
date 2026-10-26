@echo off
rem ==========================================================================
rem Arbitrator Admin - Docker and MySQL setup (Windows 10/11, x64)
rem
rem  1. Installs Docker Desktop via winget if missing
rem  2. Launches Docker Desktop and waits for its engine
rem  3. Builds the judge sandbox image (arbitrator-judge:latest)
rem  4. Creates/starts the arbitrator-mysql container and syncs the DB user
rem
rem Override defaults with DB_USER, DB_PASS, ARBITRATOR_DOCKER_IMAGE and
rem ARBITRATOR_DOCKER_PLATFORM environment variables.
rem
rem Control flow uses goto instead of nested ( ) blocks on purpose: cmd expands
rem %ERRORLEVEL% inside a block when the block is parsed, not when it runs.
rem ==========================================================================
setlocal
cd /d "%~dp0"

if not defined DB_USER set "DB_USER=arbitrator"
if not defined DB_PASS set "DB_PASS=changeme"
if not defined ARBITRATOR_DOCKER_IMAGE set "ARBITRATOR_DOCKER_IMAGE=arbitrator-judge:latest"
if not defined ARBITRATOR_DOCKER_PLATFORM set "ARBITRATOR_DOCKER_PLATFORM=linux/amd64"
set "MYSQL_CONTAINER=arbitrator-mysql"

echo ====================================================
echo    Arbitrator Admin - Docker ^& MySQL Setup (Windows)
echo ====================================================

rem --------------------------------------------------------------------------
rem STEP 1: Docker Desktop
rem --------------------------------------------------------------------------
echo [INFO] Step 1/3: Checking Docker Desktop...
where docker >nul 2>&1
if not errorlevel 1 goto docker_installed

echo [WARN] Docker Desktop is not installed.
where winget >nul 2>&1
if errorlevel 1 goto docker_manual_install
echo [INFO] Installing Docker Desktop with winget (Windows will ask for administrator approval)...
winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
if errorlevel 1 goto docker_manual_install
echo.
echo [OK] Docker Desktop was installed.
echo [INFO] Windows usually needs a sign-out or restart before Docker Desktop can run
echo [INFO] (it may also ask to enable WSL 2). Do that, then run start-server.bat again.
goto fail

:docker_manual_install
echo [ERROR] Install Docker Desktop for Windows from
echo         https://www.docker.com/products/docker-desktop/
echo         then run start-server.bat again.
goto fail

:docker_installed
docker info >nul 2>&1
if not errorlevel 1 goto docker_ready

echo [INFO] Docker engine is not running. Launching Docker Desktop...
if exist "%ProgramFiles%\Docker\Docker\Docker Desktop.exe" goto docker_launch
echo [WARN] Docker Desktop.exe was not found in its default location; start Docker Desktop manually.
goto docker_wait_init
:docker_launch
start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"

:docker_wait_init
set /a WAITED=0
:docker_wait
docker info >nul 2>&1
if not errorlevel 1 goto docker_ready
if %WAITED% geq 180 goto docker_timeout
rem ping is used as a sleep because timeout.exe fails when input is redirected.
ping -n 3 127.0.0.1 >nul
set /a WAITED+=2
goto docker_wait

:docker_timeout
echo [ERROR] Docker Desktop did not start within 180 seconds.
echo         Finish Docker Desktop's first-run setup (accept terms, enable WSL 2), then re-run.
goto fail

:docker_ready
echo [OK] Docker engine is running.

rem --------------------------------------------------------------------------
rem STEP 2: Judge sandbox image
rem --------------------------------------------------------------------------
echo [INFO] Step 2/3: Checking judge sandbox image (%ARBITRATOR_DOCKER_IMAGE%)...
docker image inspect "%ARBITRATOR_DOCKER_IMAGE%" >nul 2>&1
if not errorlevel 1 goto image_present

if exist "docker\Dockerfile" goto image_build
echo [ERROR] docker\Dockerfile is missing from this bundle; cannot build the judge image.
goto fail

:image_build
echo [INFO] Building %ARBITRATOR_DOCKER_IMAGE% for %ARBITRATOR_DOCKER_PLATFORM% (first run downloads Ubuntu + toolchains)...
docker buildx build --platform "%ARBITRATOR_DOCKER_PLATFORM%" -t "%ARBITRATOR_DOCKER_IMAGE%" --load docker
if errorlevel 1 goto image_failed
echo [OK] Judge sandbox image built successfully.
goto image_done

:image_failed
echo [ERROR] Failed to build %ARBITRATOR_DOCKER_IMAGE%. Check the build output above.
goto fail

:image_present
echo [OK] Judge sandbox image is already built.
:image_done

rem --------------------------------------------------------------------------
rem STEP 3: MySQL 8 container
rem --------------------------------------------------------------------------
echo [INFO] Step 3/3: Checking MySQL 8 database container...
docker container inspect "%MYSQL_CONTAINER%" >nul 2>&1
if errorlevel 1 goto mysql_create
docker container inspect -f "{{.State.Running}}" "%MYSQL_CONTAINER%" | findstr /c:"true" >nul
if not errorlevel 1 goto mysql_running
echo [INFO] Starting existing MySQL container '%MYSQL_CONTAINER%'...
docker start "%MYSQL_CONTAINER%" >nul
if errorlevel 1 goto mysql_start_failed
goto mysql_wait_init

:mysql_create
netstat -an | findstr /r /c:":3306 .*LISTENING" >nul
if errorlevel 1 goto mysql_run
echo [WARN] Port 3306 is already in use (probably a locally installed MySQL service).
echo [WARN] Skipping the MySQL container; the server will use the existing MySQL on port 3306.
echo [WARN] Make sure user '%DB_USER%' exists there (see init-db.sql).
goto done

:mysql_run
echo [INFO] Creating MySQL 8 container '%MYSQL_CONTAINER%'...
docker run -d --name "%MYSQL_CONTAINER%" --restart unless-stopped -p 3306:3306 -e MYSQL_DATABASE=arbitrator -e "MYSQL_USER=%DB_USER%" -e "MYSQL_PASSWORD=%DB_PASS%" -e "MYSQL_ROOT_PASSWORD=%DB_PASS%" -v arbitrator-mysql-data:/var/lib/mysql mysql:8.0 >nul
if errorlevel 1 goto mysql_start_failed
echo [OK] MySQL container created.
goto mysql_wait_init

:mysql_start_failed
echo [ERROR] Could not start the MySQL container. See the Docker error above.
goto fail

:mysql_running
echo [OK] MySQL container '%MYSQL_CONTAINER%' is already running.

:mysql_wait_init
rem TCP ping only succeeds once the final server is up, not the entrypoint's
rem temporary socket-only init server.
echo [INFO] Waiting for MySQL to accept connections...
set /a WAITED=0
:mysql_wait
docker exec -e "MYSQL_PWD=%DB_PASS%" "%MYSQL_CONTAINER%" mysqladmin ping -h 127.0.0.1 --protocol=tcp -uroot --silent >nul 2>&1
if not errorlevel 1 goto mysql_ready
if %WAITED% geq 120 goto mysql_timeout
ping -n 3 127.0.0.1 >nul
set /a WAITED+=2
goto mysql_wait

:mysql_timeout
echo [ERROR] MySQL did not become ready within 120 seconds. Check: docker logs %MYSQL_CONTAINER%
goto fail

:mysql_ready
echo [OK] MySQL is accepting connections.
echo [INFO] Synchronizing database user '%DB_USER%'...
docker exec -e "MYSQL_PWD=%DB_PASS%" "%MYSQL_CONTAINER%" mysql -h 127.0.0.1 --protocol=tcp -uroot -e "CREATE DATABASE IF NOT EXISTS arbitrator CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '%DB_USER%'@'%%'; ALTER USER '%DB_USER%'@'%%' IDENTIFIED BY '%DB_PASS%'; GRANT ALL PRIVILEGES ON arbitrator.* TO '%DB_USER%'@'%%'; CREATE USER IF NOT EXISTS '%DB_USER%'@'localhost'; ALTER USER '%DB_USER%'@'localhost' IDENTIFIED BY '%DB_PASS%'; GRANT ALL PRIVILEGES ON arbitrator.* TO '%DB_USER%'@'localhost'; FLUSH PRIVILEGES;"
if errorlevel 1 goto mysql_sync_failed
echo [OK] Database user '%DB_USER%' synchronized.
goto done

:mysql_sync_failed
echo [ERROR] Could not log in to MySQL as root. The container was probably created with a different DB_PASS.
echo         Re-run with that DB_PASS. (Resetting it with:
echo         docker rm -f %MYSQL_CONTAINER% ^&^& docker volume rm arbitrator-mysql-data
echo         DELETES all stored contest data.)
goto fail

:done
echo ====================================================
echo    Docker ^& MySQL environment is ready.
echo ====================================================
if not defined ARBITRATOR_FROM_START_SCRIPT pause
endlocal
exit /b 0

:fail
pause
endlocal
exit /b 1
