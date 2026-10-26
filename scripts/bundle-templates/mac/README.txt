Arbitrator - macOS distribution (Intel and Apple Silicon)
=========================================================

admin/   -> copy to the instructor Mac (runs the server, judge and database)
client/  -> copy to every participant Mac

INSTRUCTOR MAC
  Double-click admin/start-server.command
  (or in Terminal: cd admin && ./start-server.sh)

  First run installs Docker Desktop via Homebrew if it is missing (otherwise
  install it from docker.com), launches it, builds the judge sandbox image,
  creates the arbitrator-mysql container, then starts the server.
  Instructor console: http://localhost:8080/admin  (default admin / admin123)
  The judge image targets linux/amd64 and runs emulated on Apple Silicon. To
  judge natively on an M-series Mac, run once with
  ARBITRATOR_DOCKER_PLATFORM=linux/arm64 ./setup.sh
  before the image is first built.

PARTICIPANT MACs
  1. Edit client/server.properties and set server.host to the instructor
     machine's LAN IP address.
  2. Double-click client/start-client.command
  The right client JAR (Apple Silicon or Intel) is picked automatically.

If macOS says a .command file "cannot be opened", right-click it, choose
Open, and confirm once. A bundled jre/ folder is used when present (it must
match the Mac's CPU); otherwise Java 17 must be installed.
