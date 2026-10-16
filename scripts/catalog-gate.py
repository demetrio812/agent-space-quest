#!/usr/bin/env python3
"""SSH forced-command endpoint for discovery, observation and opt-in text input."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time

MAX_CATALOG_BYTES = 2 * 1024 * 1024
MAX_ERROR_BYTES = 64 * 1024
MAX_INPUT_BYTES = 16384
SEND_COMMAND = re.compile(r'agentspace-send-v1 ([0-9a-f]{64}) ([A-Za-z0-9_.:@%+/][A-Za-z0-9_.:@%+/-]{0,255}) ([A-Za-z0-9+/=]{1,21848})', re.ASCII)
OBSERVE_COMMAND = re.compile(r'agentspace-observe-v1 ([0-9a-f]{64}) ([A-Za-z0-9_.:@%+/][A-Za-z0-9_.:@%+/-]{0,255})', re.ASCII)


def parse_command(command):
    if command == 'agentspace-catalog-v1':
        return ('catalog',)
    match = OBSERVE_COMMAND.fullmatch(command)
    if match:
        return ('observe', match[1], match[2])
    match = SEND_COMMAND.fullmatch(command)
    if match:
        return ('send', match[1], match[2], match[3])
    raise ValueError('Unsupported AgentSpace command.')


def server_identity(socket_path):
    socket = Path(socket_path)
    stat = socket.stat()  # A missing server must never become an implicit launch.
    identity = f'{socket.resolve()}:{stat.st_dev}:{stat.st_ino}:{stat.st_birthtime}'
    return hashlib.sha256(identity.encode()).hexdigest()


def bounded_catalog(binary, env, arguments=('agent', 'list'), deadline=None):
    """Drain both pipes with size and wall-clock limits, including noisy failures."""
    with subprocess.Popen([binary, *arguments], env=env, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        try:
            output = bytearray()
            error_bytes = 0
            deadline = deadline if deadline is not None else time.monotonic() + 4
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ, 'stdout')
                selector.register(process.stderr, selectors.EVENT_READ, 'stderr')
                while selector.get_map():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise RuntimeError('Herdr catalog request timed out')
                    for key, _ in selector.select(remaining):
                        chunk = os.read(key.fileobj.fileno(), 8192)
                        if not chunk:
                            selector.unregister(key.fileobj)
                        elif key.data == 'stdout':
                            output.extend(chunk)
                            if len(output) > MAX_CATALOG_BYTES:
                                raise RuntimeError('Herdr catalog response exceeds limit')
                        else:
                            error_bytes += len(chunk)
                            if error_bytes > MAX_ERROR_BYTES:
                                raise RuntimeError('Herdr catalog error output exceeds limit')
                if process.wait(timeout=max(0.001, deadline - time.monotonic())):
                    raise RuntimeError('Herdr catalog request failed')
            return output
        except BaseException:
            process.kill()
            process.wait()
            raise


def catalog(binary, socket_path, include_workspaces=False):
    identity = server_identity(socket_path)
    env = os.environ.copy()
    env['HERDR_SOCKET_PATH'] = str(Path(socket_path))
    deadline = time.monotonic() + 4
    response = json.loads(bounded_catalog(binary, env, deadline=deadline))
    if (not isinstance(response, dict) or response.get('error') is not None
            or response.get('ok') is False or response.get('success') is False
            or not isinstance(response.get('result'), dict)
            or not isinstance(response['result'].get('agents'), list)):
        raise RuntimeError('Herdr did not return an agent catalog')
    workspaces = []
    tabs = []
    if include_workspaces:
        try:
            listing = json.loads(bounded_catalog(binary, env, ('workspace', 'list'), deadline))
            result = listing.get('result', {}).get('workspaces', [])
            if isinstance(result, list) and len(result) <= 4096 and not listing.get('error'):
                workspaces = result
        except (RuntimeError, ValueError, OSError, AttributeError):
            pass  # Older Herdr servers can still provide sessions without workspace labels.
    if include_workspaces:
        try:
            listing = json.loads(bounded_catalog(binary, env, ('tab', 'list'), deadline))
            result = listing.get('result', {}).get('tabs', [])
            if isinstance(result, list) and len(result) <= 4096 and not listing.get('error'):
                tabs = result
        except (RuntimeError, ValueError, OSError, AttributeError):
            pass
    if identity != server_identity(socket_path):
        raise RuntimeError('Herdr restarted during the catalog request')
    return {'serverIdentity': identity, 'catalog': response, 'workspaces': workspaces, 'tabs': tabs}


def observe(binary, socket_path, identity, terminal):
    # Validate even direct callers; neither argument ever reaches a shell.
    parse_command(f'agentspace-observe-v1 {identity} {terminal}')
    if server_identity(socket_path) != identity:
        raise RuntimeError('Herdr server identity changed')
    current = catalog(binary, socket_path)
    if current['serverIdentity'] != identity:
        raise RuntimeError('Herdr server identity changed')
    matches = [agent for agent in current['catalog']['result']['agents']
               if isinstance(agent, dict) and agent.get('terminal_id') == terminal]
    if len(matches) != 1:
        raise RuntimeError('Herdr terminal is no longer present')
    if server_identity(socket_path) != identity:
        raise RuntimeError('Herdr server identity changed')
    env = os.environ.copy()
    env['HERDR_SOCKET_PATH'] = str(Path(socket_path))
    dimensions = []
    pane = matches[0].get('pane_id', '')
    if isinstance(pane, str) and re.fullmatch(r'[A-Za-z0-9_.:@%+/][A-Za-z0-9_.:@%+/-]{0,255}', pane):
        try:
            listing = json.loads(bounded_catalog(binary, env, ('pane', 'layout', '--pane', pane)))
            panes = listing.get('result', {}).get('layout', {}).get('panes', [])
            rows = [row for row in panes if isinstance(row, dict) and row.get('pane_id') == pane]
            if len(rows) == 1 and not listing.get('error') and listing.get('ok') is not False:
                rect = rows[0].get('rect', {})
                width, height = rect.get('width'), rect.get('height')
                # Match Android ObserveProtocol bounds, including total cell count.
                if (type(width) is int and type(height) is int and 1 <= width <= 512
                        and 1 <= height <= 256 and width * height <= 65536):
                    dimensions = ['--cols', str(width), '--rows', str(height)]
        except (RuntimeError, ValueError, OSError, AttributeError, TypeError):
            pass  # Older Herdr versions can still use the default observer projection.
    if server_identity(socket_path) != identity:
        raise RuntimeError('Herdr server identity changed')
    with open(os.devnull, 'rb') as empty:
        os.dup2(empty.fileno(), 0)
    os.execve(binary, [binary, 'terminal', 'session', 'observe', terminal, *dimensions], env)


def send_text(binary, socket_path, identity, terminal, encoded, allow_input=False):
    if not allow_input:
        raise ValueError('Input is not enabled for this headset key.')
    parse_command(f'agentspace-send-v1 {identity} {terminal} {encoded}')
    raw = base64.b64decode(encoded, validate=True)
    if not 1 <= len(raw) <= MAX_INPUT_BYTES or base64.b64encode(raw).decode() != encoded:
        raise ValueError('Invalid input size or encoding')
    text = raw.decode('utf-8', errors='strict')
    if not text.strip() or any((ord(c) < 32 and c not in '\n\t') or 127 <= ord(c) < 160 for c in text):
        raise ValueError('Input contains terminal control characters')
    if server_identity(socket_path) != identity:
        raise RuntimeError('Herdr server identity changed')
    current = catalog(binary, socket_path)
    targets = [a for a in current['catalog']['result']['agents']
               if isinstance(a, dict) and a.get('terminal_id') == terminal]
    if current['serverIdentity'] != identity or len(targets) != 1:
        raise RuntimeError('Terminal unavailable')
    pane = targets[0].get('pane_id', '')
    if not isinstance(pane, str) or not re.fullmatch(r'[A-Za-z0-9_.:@%+/][A-Za-z0-9_.:@%+/-]{0,255}', pane):
        raise RuntimeError('Invalid pane identity')
    if server_identity(socket_path) != identity:
        raise RuntimeError('Herdr server identity changed')
    env = os.environ.copy()
    env['HERDR_SOCKET_PATH'] = str(Path(socket_path))
    # The Herdr run CLI consumes the remaining argv literally, including leading dashes.
    # No retries: a failed acknowledgement may still mean the terminal received the text.
    output = bounded_catalog(binary, env, ('pane', 'run', pane, text))
    # Current Herdr acknowledges successful pane.run with exit 0 and no stdout.
    response = json.loads(output) if output.strip() else {'result': {'type': 'ok'}}
    if (not isinstance(response, dict) or response.get('error') is not None
            or response.get('ok') is False or response.get('success') is False or 'result' not in response):
        raise RuntimeError('Herdr did not acknowledge input')
    return {'sent': True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--herdr', required=True)
    parser.add_argument('--socket', required=True)
    parser.add_argument('--allow-input', action='store_true', help='Allow explicit text + Enter for validated catalog sessions')
    args = parser.parse_args()
    try:
        command = parse_command(os.environ.get('SSH_ORIGINAL_COMMAND', ''))
        if os.environ.get('SSH_TTY'):
            raise ValueError('AgentSpace observation does not permit a PTY.')
        if command[0] == 'catalog':
            payload = catalog(args.herdr, args.socket, include_workspaces=True)
            payload['inputEnabled'] = args.allow_input
            print(json.dumps(payload, separators=(',', ':')))
        elif command[0] == 'send':
            print(json.dumps(send_text(args.herdr, args.socket, *command[1:], allow_input=args.allow_input)))
        else:
            observe(args.herdr, args.socket, command[1], command[2])
    except ValueError:
        sys.exit('AgentSpace command rejected; input requires an explicitly enabled headset key.')
    except Exception:
        sys.exit('Herdr unavailable or session identity changed; refresh the selected server catalog.')


if __name__ == '__main__':
    main()
