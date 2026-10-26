Arbitrator - Linux distribution (Ubuntu 22.04 "Jammy", x86_64)
==============================================================

admin/   -> copy to the instructor PC (runs the server, judge and database)
client/  -> copy to every participant PC

INSTRUCTOR PC
  cd admin
  ./start-server.sh

  First run installs Docker Engine (sudo password requested), builds the
  judge sandbox image, creates the arbitrator-mysql container, then starts
  the server. Later runs skip whatever is already in place.
  Instructor console: http://localhost:8080/admin  (default admin / admin123)
  ./setup.sh alone prepares Docker and MySQL without starting the server.

PARTICIPANT PCs
  1. Edit client/server.properties and set server.host to the instructor
     PC's LAN IP address.
  2. cd client && ./start-client.sh
  No Docker or MySQL is needed on participant PCs.

Java: a bundled jre/ folder is used when present; otherwise Java 17 must be
installed. Database credentials default to arbitrator / changeme; override
with DB_USER=... DB_PASS=... ./start-server.sh (use the same values every run).
