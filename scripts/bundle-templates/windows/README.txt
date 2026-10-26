Arbitrator - Windows distribution (Windows 10/11, x64)
======================================================

admin\   -> copy to the instructor PC (runs the server, judge and database)
client\  -> copy to every participant PC

INSTRUCTOR PC
  Double-click admin\start-server.bat

  First run installs Docker Desktop with winget if it is missing. Windows
  then needs a sign-out/restart (and may enable WSL 2); run start-server.bat
  again afterwards. The script launches Docker Desktop, builds the judge
  sandbox image, creates the arbitrator-mysql container, then starts the
  server. Keep the window open while the contest runs.
  Instructor console: http://localhost:8080/admin  (default admin / admin123)
  setup.bat alone prepares Docker and MySQL without starting the server.
  Allow Java through Windows Firewall when asked, so participants can connect.

PARTICIPANT PCs
  1. Edit client\server.properties and set server.host to the instructor
     PC's LAN IP address.
  2. Double-click client\start-client.bat
  No Docker or MySQL is needed on participant PCs.

Java: a bundled jre\ folder is used when present; otherwise Java 17 must be
installed. Database credentials default to arbitrator / changeme; set DB_USER
and DB_PASS environment variables to override (use the same values every run).
