# Bounded test-only probes through SandboxExecutor, not source-ban policy.
import ctypes
import errno
import os
from pathlib import Path
import signal
import socket
import sys
import threading
import time

mode = input()
if mode == 'control':
    Path('/tmp/recovery-control').write_text('ordinary-execution')
    assert Path('/tmp/recovery-control').read_text() == 'ordinary-execution'
    print('control-ok')
elif mode == 'threads':
    threading.stack_size(64 * 1024)
    gate = threading.Event()
    threads = []
    limited = False
    try:
        for _ in range(128):
            worker = threading.Thread(target=gate.wait)
            try:
                worker.start()
            except RuntimeError:
                limited = True
                break
            threads.append(worker)
        assert limited and 1 < len(threads) < 64, 'thread pressure was not limited'
    finally:
        gate.set()
        for worker in threads:
            worker.join(timeout=1)
            assert not worker.is_alive()
    recovered = threading.Thread(target=lambda: None)
    recovered.start()
    recovered.join(timeout=1)
    assert not recovered.is_alive()
    print('threads-contained')
elif mode == 'tmpfs':
    paths = []
    block = b'x' * (1024 * 1024)
    filled = 0
    limited = False
    try:
        # At most 5 * 16 MiB attempted; below the runtime memory hard cap.
        for index in range(5):
            path = Path('/tmp/bounded-fill-' + str(index))
            paths.append(path)
            with path.open('wb', buffering=0) as output:
                try:
                    for _ in range(16):
                        filled += output.write(block)
                except OSError as failure:
                    assert failure.errno == errno.ENOSPC, 'unexpected disk failure'
                    limited = True
            if limited:
                break
        assert limited and 32 * 1024 * 1024 <= filled <= 64 * 1024 * 1024
    finally:
        for path in paths:
            path.unlink(missing_ok=True)
    Path('/tmp/disk-recovery').write_text('recovered')
    assert Path('/tmp/disk-recovery').read_text() == 'recovered'
    print('tmpfs-contained')
elif mode == 'file-size':
    # Sparse resize: enforce RLIMIT_FSIZE without allocating host disk.
    with Path('/tmp/file-size-probe').open('wb') as output:
        try:
            output.truncate(65 * 1024 * 1024)
        except OSError as failure:
            assert failure.errno == errno.EFBIG
        else:
            raise AssertionError('file-size limit bypassed')
    assert Path('/tmp/file-size-probe').stat().st_size == 0
    print('file-size-contained')
elif mode == 'privilege':
    status = dict(line.split(':', 1) for line in
                  Path('/proc/self/status').read_text().splitlines() if ':' in line)
    assert os.getuid() != 0 and status['NoNewPrivs'].strip() == '1'
    for key in ('CapInh', 'CapPrm', 'CapEff', 'CapBnd', 'CapAmb'):
        assert int(status[key].strip(), 16) == 0
    try:
        os.setuid(0)
    except OSError as failure:
        assert failure.errno == errno.EPERM
    else:
        raise AssertionError('became root')
    libc = ctypes.CDLL(None, use_errno=True)
    # Harmless namespace creation attempts; no mounts/host data are changed.
    for flag in (0x10000000, 0x00020000, 0x40000000):  # USER, NEWNS, NEWNET
        ctypes.set_errno(0)
        assert libc.unshare(flag) == -1
        assert ctypes.get_errno() in (errno.EPERM, errno.ENOSYS)
    print('privilege-contained')
elif mode in ('signals', 'detached-timeout', 'orphan'):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    if mode != 'signals':
        read_fd, write_fd = os.pipe()
        child = os.fork()
        if child == 0:
            os.close(read_fd)
            os.setsid()  # Escape the original process group, not the container.
            os.write(write_fd, b'ready')
            os.close(write_fd)
            # Bounded by both Docker/timeout and a private 10-second fallback.
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                time.sleep(0.1)
            os._exit(0)
        os.close(write_fd)
        assert os.read(read_fd, 5) == b'ready'
        os.close(read_fd)
    print(socket.gethostname(), flush=True)
    print(mode + '-ready', flush=True)
    if mode != 'orphan':
        while True:
            time.sleep(0.1)
elif mode == 'host-loopback':
    port = int(input())
    assert 0 < port < 65536
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.3):
            pass
    except OSError:
        pass
    else:
        raise AssertionError('host listener reachable')
    print('host-loopback-contained')
else:
    raise AssertionError('unknown mode')
