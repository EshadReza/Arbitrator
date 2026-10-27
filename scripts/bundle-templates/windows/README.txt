Arbitrator - Windows distribution (Windows 10/11, x64)
======================================================

admin\   -> copy to the instructor PC (runs the server, judge and database)
client\  -> copy to every participant PC

Java is included (jre\ folder). If you received a ZIP file, right-click it and
choose "Extract All" first. The scripts do not work from inside a ZIP.

INSTRUCTOR PC - one script
  Double-click admin\start-server.bat

  It checks the bundle, then:
    1. Finds Docker Desktop. If it is missing, the script installs it with
       winget (Windows asks for administrator approval). Windows then needs a
       restart; start Docker Desktop once, accept its terms, and double-click
       start-server.bat again.
    2. Starts Docker Desktop and switches it to Linux containers if needed.
    3. Loads the judge and MySQL images from admin\images\ (no download).
    4. Runs its own MySQL on 127.0.0.1, port 3306 or the next free port, so a
       MySQL already installed on the PC does not interfere.
    5. Adds a Windows Firewall rule for port 8080 (asks for approval once) and
       starts the server.
  Instructor console: http://localhost:8080/admin  (default admin / admin123)
  Keep the window open while the contest runs.

  If anything fails, the window stays open with the reason. Messages are
  saved in admin\logs\start-server.log and the server log in admin\logs\server.log.

  Docker Desktop needs hardware virtualization and WSL 2. If it cannot start,
  Docker Desktop itself shows what to enable.

PARTICIPANT PCs - one script
  1. Open client\server.properties in Notepad and set server.host to the
     instructor PC's LAN IP address (shown in the server window as
     "Students connect to").
  2. Double-click client\start-client.bat
  Participant PCs need no Docker, MySQL, Java installation or administrator rights.

If Windows shows "Windows protected your PC" for a .bat file, click
"More info", then "Run anyway".
