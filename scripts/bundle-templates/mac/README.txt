Arbitrator - macOS distribution (Intel and Apple Silicon)
=========================================================

admin/   -> copy to the instructor Mac (runs the server, judge and database)
client/  -> copy to every participant Mac

Java is included for both Mac types (jre-x64/ for Intel, jre-aarch64/ for
Apple Silicon); the right one is picked automatically.

INSTRUCTOR MAC - one script
  Double-click admin/start-server.command
  (or in Terminal: bash admin/start-server.sh)

  It installs Docker Desktop with Homebrew if it is missing (otherwise install
  it from docker.com), starts it, loads the judge and MySQL images from
  admin/images/, runs its own MySQL on 127.0.0.1 (port 3306 or the next free
  port), and starts the server.
  Instructor console: http://localhost:8080/admin  (default admin / admin123)
  If macOS asks whether Java may accept incoming connections, choose Allow.

  If anything fails, the window stays open with the reason, and the output
  is saved in admin/logs/.

PARTICIPANT MACs - one script
  1. Edit client/server.properties and set server.host to the instructor
     machine's LAN IP address.
  2. Double-click client/start-client.command

If macOS says a .command file "cannot be opened" or is from an unidentified
developer, right-click it, choose Open, and confirm once.
