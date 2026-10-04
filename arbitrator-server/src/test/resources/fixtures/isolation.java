// Test-only, bounded probes. No real secrets or network payloads.
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
public class Main {
    static void require(boolean ok) { if (!ok) throw new AssertionError("isolation assertion failed"); }
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String mode = input.readLine();
        if (mode.equals("control")) { System.out.println("control-ok"); return; }
        if (mode.equals("loop")) { for (;;) {} }
        if (mode.equals("stdout") || mode.equals("stderr")) {
            PrintStream stream = mode.equals("stdout") ? System.out : System.err;
            stream.print("x".repeat(2 * 1024 * 1024)); stream.flush(); return;
        }
        require(mode.equals("isolation"));
        String host = input.readLine(), sibling = input.readLine();
        require(Files.readString(Path.of("/sandbox/positive-control.txt")).equals("mounted-control"));
        // Linux status is available even though host system statistics remain intentionally visible.
        String uid = Files.readAllLines(Path.of("/proc/self/status")).stream()
            .filter(line -> line.startsWith("Uid:")).findFirst().orElseThrow();
        require(!uid.split("\\s+")[1].equals("0"));
        require(!Files.exists(Path.of("/var/run/docker.sock")));
        require(!Files.exists(Path.of("/run/docker.sock")));
        for (String name : List.of(host, sibling, "/proc/1/root" + host, "/proc/1/root" + sibling,
                "../fake-host-secret.txt", "../fake-sibling/fake-secret.txt", "/var/run/docker.sock")) {
            boolean opened = false;
            try (InputStream ignored = Files.newInputStream(Path.of(name))) { opened = true; }
            catch (IOException expected) {}
            require(!opened);
        }
        for (String name : List.of(host, sibling, "/etc/passwd", "/etc/forbidden-write",
                "/sandbox/forbidden-write", "/sys/forbidden-write", "/proc/sys/kernel/hostname")) {
            boolean opened = false;
            // No TRUNCATE_EXISTING: even an unexpected permission must not destroy a real file.
            try (OutputStream ignored = Files.newOutputStream(Path.of(name), StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE)) { opened = true; }
            catch (IOException expected) {}
            require(!opened);
        }
        Files.writeString(Path.of("/tmp/allowed-control"), "private-tmp");
        require(Files.readString(Path.of("/tmp/allowed-control")).equals("private-tmp"));
        require("/tmp".equals(System.getenv("HOME")));
        require("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".equals(System.getenv("PATH")));
        for (String key : List.of("LD_PRELOAD", "LD_LIBRARY_PATH", "LIBRARY_PATH", "CPATH", "CPLUS_INCLUDE_PATH",
                "PYTHONHOME", "PYTHONPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "ENV",
                "BASH_ENV", "ARBITRATOR_JWT_SECRET", "DB_PASSWORD", "AWS_SECRET_ACCESS_KEY")) {
            String value = System.getenv(key); require(value == null || value.isEmpty());
        }
        List<String> routes = Files.readAllLines(Path.of("/proc/net/route"));
        for (String row : routes.subList(1, routes.size())) {
            require(row.isBlank() || row.trim().split("\\s+")[0].equals("lo"));
        }
        try (ServerSocket local = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", local.getLocalPort()), 200);
            try (Socket accepted = local.accept()) { require(accepted.isConnected()); }
        }
        String[] targets = {"169.254.169.254", "1.1.1.1"}; int[] ports = {80, 53};
        for (int i = 0; i < targets.length; i++) {
            boolean connected = false;
            try (Socket channel = new Socket()) {
                channel.connect(new InetSocketAddress(targets[i], ports[i]), 200); connected = true;
            } catch (IOException expected) {}
            // Failure plus the route-table check is evidence, not failure alone.
            require(!connected);
        }
        System.out.println("isolation-ok");
    }
}
