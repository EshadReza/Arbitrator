# Test-only, bounded probes. Never use real secrets or print system metadata.
import errno
import os
from pathlib import Path
import socket
import sys

mode = input()
if mode == 'control':
    print('control-ok')
elif mode == 'loop':
    while True:
        pass
elif mode in ('stdout', 'stderr'):
    stream = sys.stdout if mode == 'stdout' else sys.stderr
    stream.write('x' * (2 * 1024 * 1024))
    stream.flush()
elif mode == 'isolation':
    host, sibling = input(), input()
    assert Path('/sandbox/positive-control.txt').read_text() == 'mounted-control'
    assert os.getuid() != 0
    # Opening a Unix socket as a file can fail even when it is mounted;
    # assert the daemon endpoints themselves are absent as well.
    assert not Path('/var/run/docker.sock').exists()
    assert not Path('/run/docker.sock').exists()
    for name in (host, sibling, '/proc/1/root' + host, '/proc/1/root' + sibling,
                 '../fake-host-secret.txt', '../fake-sibling/fake-secret.txt',
                 '/var/run/docker.sock'):
        try:
            with open(name, 'rb'):
                pass
        except OSError:
            pass
        else:
            raise AssertionError('private path opened')
    for name in (host, sibling, '/etc/passwd', '/etc/forbidden-write',
                 '/sandbox/forbidden-write', '/sys/forbidden-write',
                 '/proc/sys/kernel/hostname'):
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT, 0o600)
        except OSError:
            pass
        else:
            os.close(fd)
            raise AssertionError('forbidden write opened')
    Path('/tmp/allowed-control').write_text('private-tmp')
    assert Path('/tmp/allowed-control').read_text() == 'private-tmp'
    assert os.environ['HOME'] == '/tmp'
    assert os.environ['PATH'] == '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
    for key in ('LD_PRELOAD', 'LD_LIBRARY_PATH', 'LIBRARY_PATH', 'CPATH',
                'CPLUS_INCLUDE_PATH', 'PYTHONHOME', 'PYTHONPATH', 'JAVA_TOOL_OPTIONS',
                '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'ENV', 'BASH_ENV',
                'ARBITRATOR_JWT_SECRET', 'DB_PASSWORD', 'AWS_SECRET_ACCESS_KEY'):
        assert not os.getenv(key), 'unexpected environment value'
    assert all(row.split()[0] == 'lo' for row in
               Path('/proc/net/route').read_text().splitlines()[1:] if row.strip())
    # Local socket support is a positive control; no network payload is sent.
    with socket.socket() as local:
        local.bind(('127.0.0.1', 0))
        local.listen(1)
        with socket.create_connection(local.getsockname(), timeout=0.2):
            connection, _ = local.accept()
            connection.close()
    for address, port in (('169.254.169.254', 80), ('1.1.1.1', 53)):
        with socket.socket() as channel:
            channel.settimeout(0.2)
            assert channel.connect_ex((address, port)) in (errno.ENETUNREACH, errno.EHOSTUNREACH)
    print('isolation-ok')
else:
    raise AssertionError('unknown mode')
