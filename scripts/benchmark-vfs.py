#!/usr/bin/env python3
"""Run reproducible VFS diagnostic benchmarks against a compiled checkout (JDK 21+)."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', default=os.environ.get('VFS_PERF_JAVA', 'java'))
parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1],
                    help='Checkout supplying compiled VFS classes and the NeoForge test classpath.')
parser.add_argument('--scenario', choices=['main', 'extra', 'contention', 'zip-reuse'], default='main')
parser.add_argument('--output', type=Path)
args = parser.parse_args()
repo = args.repo.expanduser().resolve()
classpath_file = repo / 'modules/core/build/moddev/junit/legacyClasspath.txt'
classes = repo / 'modules/core/build/classes/java/main'
if not classpath_file.exists() or not classes.exists():
    parser.error('Build the selected checkout first: ./gradlew :modules:core:testClasses :modules:core:writeNeoForgeTestClasspath')
classpath = [str(classes)] + classpath_file.read_text().splitlines()
output = (args.output or repo / 'build/reports/vfs-performance' / (args.scenario + '.jsonl')).resolve()
output.parent.mkdir(parents=True, exist_ok=True)
source = Path(__file__).resolve().parent / 'benchmarks/VfsPerfProbe.java'
command = [args.java, '-Xms512m', '-Xmx1536m', '-XX:+UseG1GC', '-cp', os.pathsep.join(classpath), str(source), str(output)]
if args.scenario != 'main':
    command.append('zipReuse' if args.scenario == 'zip-reuse' else args.scenario)
raise SystemExit(subprocess.run(command, cwd=repo).returncode)
