#!/usr/bin/env python3
"""Resolve Git metadata for Windows worktrees mounted inside WSL."""
import os
from pathlib import Path
import re


def git_context(root, environ=None, mount_root='/mnt'):
    """Return an environment and Git options usable from ``root``.

    Git for Windows writes an absolute Windows path into linked-worktree .git
    files. WSL Git cannot resolve that path directly, even though the target
    gitdir is mounted under /mnt/<drive>. Preserve normal Git behavior for
    native repositories and only add overrides for a valid mapped gitdir.
    """
    root = Path(root).resolve()
    environment = dict(os.environ if environ is None else environ)
    git_file = root / '.git'
    if not git_file.is_file():
        return environment, []

    try:
        contents = git_file.read_text(encoding='utf-8').strip()
    except OSError:
        return environment, []
    match = re.fullmatch(r'gitdir:\s*(.+)', contents, flags=re.IGNORECASE)
    if not match:
        return environment, []

    windows_path = match.group(1).strip()
    drive_match = re.fullmatch(r'([A-Za-z]):[\\/](.*)', windows_path)
    if not drive_match:
        return environment, []
    drive, suffix = drive_match.groups()
    mapped_gitdir = Path(mount_root) / drive.lower() / Path(*re.split(r'[\\/]+', suffix))
    if not mapped_gitdir.is_dir():
        return environment, []

    environment['GIT_DIR'] = str(mapped_gitdir)
    environment['GIT_WORK_TREE'] = str(root)
    return environment, ['-c', 'core.autocrlf=true']
