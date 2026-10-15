#!/usr/bin/env python3
"""Summarize camera JFRs; sampled allocation weights are not allocation totals."""
import argparse
import collections
import datetime
import json
import subprocess


def analyze(path):
    kinds = 'jdk.ThreadAllocationStatistics,jdk.ExecutionSample,jdk.ObjectAllocationSample,kasuga.CameraRenderPass,kasuga.CameraChunkSnapshot'
    result = subprocess.run(['jfr', 'print', '--json', '--stack-depth', '48', '--events', kinds, path],
                            check=True, capture_output=True, text=True)
    allocation = collections.defaultdict(list)
    passes = collections.defaultdict(list)
    cpu, samples = collections.Counter(), collections.Counter()
    chunks = 0
    for event in json.loads(result.stdout)['recording']['events']:
        values, kind = event['values'], event['type']
        if kind == 'jdk.ThreadAllocationStatistics':
            time = datetime.datetime.fromisoformat(values['startTime'].replace('Z', '+00:00'))
            allocation[values['thread']['javaName']].append((time, values['allocated']))
        elif kind == 'kasuga.CameraRenderPass':
            passes[values['viewId']].append(float(values['duration'][2:-1]) * 1000)
        elif kind == 'kasuga.CameraChunkSnapshot':
            chunks += 1
        frames = (values.get('stackTrace') or {}).get('frames', [])
        names = [f['method']['type']['name'] + '.' + f['method']['name'] for f in frames]
        if kind == 'jdk.ExecutionSample' and names:
            cpu[names[0]] += 1
        if kind == 'jdk.ObjectAllocationSample':
            for marker in ['CloudRenderer', 'CubicSampler', 'ScalarGaussianSampler']:
                if any(marker in name for name in names):
                    samples[marker] += 1
    rates = {}
    for thread, values in allocation.items():
        values.sort()
        first, last = values[0], values[-1]
        seconds = (last[0] - first[0]).total_seconds()
        if seconds > 0:
            rates[thread] = {'samples': len(values), 'seconds': seconds,
                             'allocatedMBPerSecond': (last[1] - first[1]) / seconds / 1e6}
    camera_passes = {}
    for view, times in passes.items():
        times.sort()
        camera_passes[view] = {'count': len(times), 'meanMs': sum(times) / len(times),
                               'p95Ms': times[int(len(times) * .95)]}
    return {'recording': path, 'threadAllocation': rates, 'cameraPasses': camera_passes,
            'chunkSnapshots': chunks, 'allocationStackSampleCounts': dict(samples),
            'topCpuSamples': cpu.most_common(12),
            'limitations': 'Allocation samples locate hot paths; first sample weights may include pre-recording allocation. '
                           'ThreadAllocationStatistics deltas give rates; CPU samples do not measure GPU completion.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recordings', nargs='+')
    parser.add_argument('--output')
    args = parser.parse_args()
    output = json.dumps([analyze(path) for path in args.recordings], indent=2)
    if args.output:
        with open(args.output, 'w', encoding='utf-8') as target:
            target.write(output + '\n')
    else:
        print(output)
