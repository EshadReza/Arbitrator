Arbitrator - Linux distribution (Ubuntu 26.04 "Resolute Raccoon", x86_64)
=========================================================================

admin/   -> copy to the instructor PC (runs the server, judge and database)
client/  -> copy to every participant PC

INSTRUCTOR PC - one script
  cd admin
  ./start-server.sh

  The first run installs Docker from the Ubuntu archive (sudo password
  requested), builds the judge sandbox image, creates the arbitrator-mysql
  container, and starts the server. Later runs skip any step that is already
  done. Instructor console: http://localhost:8080/admin (default admin / admin123)
  ./start-server.sh --setup-only prepares Docker and MySQL without starting
  the server.

  If an images/ folder is present, the MySQL and judge images are loaded
  from it and nothing is downloaded from Docker Hub. Downloads that are
  needed are retried automatically.

  If something fails, the window stays open showing the error, and the
  full output is saved in admin/logs/ (send that file when asking for help).
  A MySQL already installed on the PC is left alone; the bundle runs its own
  on the next free port.

PARTICIPANT PCs - one script
  1. Edit client/server.properties and set server.host to the instructor
     PC's LAN IP address.
  2. cd client && ./start-client.sh
  Participant PCs do not need Docker or MySQL.

Java: the bundled jre/ folder is used when present. Without it, the scripts
install OpenJDK 21 from the Ubuntu archive. Database credentials default to
arbitrator / changeme; override them with DB_USER=... DB_PASS=... ./start-server.sh
(use the same values on every run).

NO "RUN AS EXECUTABLE" / NO SUDO
  If the "Executable as Program" switch keeps turning itself off, or chmod
  has no effect, the folder is on a drive without Linux permissions (a
  FAT32/exFAT/NTFS USB stick or a network folder). Copy it into your home
  folder first, then start it with bash. Neither step needs sudo:
      cp -r /media/$USER/<usb>/client ~/arbitrator-client
      bash ~/arbitrator-client/start-client.sh
  The scripts restore the bundled Java's permissions themselves.

  Participant PCs never need sudo when the jre/ folder is bundled.
  The instructor PC needs Docker. On an account without sudo, ask the lab
  administrator to run once:
      sudo apt-get install -y docker.io docker-buildx
      sudo usermod -aG docker <your-user>
  then log out and back in. The script says so if this is missing.
