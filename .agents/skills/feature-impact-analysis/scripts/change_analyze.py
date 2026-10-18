#!/usr/bin/env python3
"""变更影响面分析器 —— 生成 PR 所需三件套的第一件（草稿）。

为什么存在：见 doc/process/CHANGE-IMPACT-PROCESS.md
风险画像的定义与真实事故来源：见 doc/process/RISK-PROFILES.md

本脚本只做机械提取，**不替代判断**：
它认不出语义耦合、反向依赖、运行时才显现的问题。
它的输出是草稿，必须由人或 agent 再过一遍。

跨平台：只用 Python 标准库，无第三方依赖，Windows / Linux / macOS 通用。
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

def _resolve_repo_root(explicit=None):
    """定位 git 仓库根。

    脚本可能被放在 skill 目录（.agents/...，本身不在任何仓库里）或仓库的 scripts/ 下，
    因此不能靠 __file__ 的相对层级。顺序：
      1. --repo 显式指定
      2. 从当前工作目录向上找
      3. 从脚本所在目录向上找（兜底）
    """
    if explicit:
        p = Path(explicit).expanduser().resolve()
        if p.is_dir():
            return p
        raise SystemExit(f'--repo 不是目录: {explicit}')
    for start in (Path.cwd(), Path(__file__).resolve().parent):
        proc = subprocess.run(['git', 'rev-parse', '--show-toplevel'],
                              cwd=start, capture_output=True, text=True)
        if proc.returncode == 0 and proc.stdout.strip():
            return Path(proc.stdout.strip())
    raise SystemExit(
        '找不到 git 仓库根。请在目标仓库目录内执行，或用 --repo <路径> 指定。'
    )


REPO_ROOT = None  # 在 main() 里按 --repo / cwd 解析

# ── 风险画像关键词表 ──────────────────────────────────────────────
# 每条对应 RISK-PROFILES.md 里的一条画像。命中只是起点，不是判定。
# 刻意保持宽松（宁可多报让人排除，也不要漏报后无人察觉）。
RISK_PATTERNS = {
    # P-STATE 只收「资源生命周期」和「跨持有者共享状态」的强信号。
    # 早期版本把 add(/remove(/put( 算进去，结果 994 个 java 文件里 491 个命中 ——
    # 等于没报。教训：宁可漏报让人去看，也不能让信号淹没在噪声里。
    'P-STATE': (
        r'memFree|memCalloc|memAlloc'                        # 堆外内存：分配/释放必须配对
        r'|\b(?:glDelete|glGen|freeGL)\w*'
        r'|allocateDirect'
        r'|\b(?:is|was)Closed\b'
        r'|AutoCloseable'
        r'|\bclose\s*\(\s*\)'
        r'|\b(?:un)?subscribe\s*\('
        r'|SharedBuffer|ThreadSafe'
    ),
    'P-ORDER': (
        # Registry / manifest / tab / topolog 是强信号。
        # HashMap/HashSet/LinkedHash 是提示级：P-ORDER 的核心风险恰恰是
        # 「依赖了迭代顺序」，所以改了用 HashMap 的代码值得看一眼。
        # 在 994 个 java 文件的 diff 上这会带来约 1/3 的命中率 ——
        # 偏宽，但换的是不漏掉 NKY#143/#144 那类「同一个代码不同机器结果不同」的 bug。
        r'Registry|registry|manifest|MANIFEST|CreativeModeTab'
        r'|HashMap|HashSet|LinkedHash|\.sort\s*\(|topolog|duplicate|last-wins'
    ),
    'P-PIXEL': (
        r'\b(?:blend|alpha|depthMask|Framebuffer|FrameBuffer|RenderPass|colorMask|BlendState)\b'
        r'|VertexFormat|glDeleteTextures|glGenFramebuffers|glBlendFunc|glGenVertexArrays'
    ),
    'P-XMOD': (
        r'\bmixin\b|\bMixin\b|@Inject|@Redirect|@ModifyArg|@Overwrite'
        r'|IMixinConfigPlugin|addGlobalApi|@Api\b|getPointer'
    ),
    'P-NATIVE': (
        r'\b(?:jni|JNI|javet|box3d|dlopen)\b|loadLibrary|System\.load'
        r'|UnsatisfiedLink|MethodHandle|Class\.forName|getDeclaredMethod|getDeclaredField|setAccessible'
    ),
    'P-SAVE': (
        r'NbtCompound|CompoundTag|writeAdditional|readAdditional|saveAdditional'
        r'|savedData|\bpersist\b'
    ),
}

RISK_NAMES = {
    'P-STATE': '状态一致性漂移',
    'P-ORDER': '顺序与确定性依赖',
    'P-PIXEL': '渲染只在像素级可见',
    'P-XMOD': '模组间交互',
    'P-NATIVE': '原生库与平台差异',
    'P-SAVE': '存档与持久化迁移',
}

SCAN_SUFFIXES = {'.java', '.kt', '.gradle', '.glsl', '.vsh', '.fsh', '.shader', '.frag', '.vert'}

MIXIN_HINT = re.compile(r'mixin|@Inject|@Redirect|@ModifyArg|@Overwrite|IMixinConfigPlugin', re.I)

# P-STATE 的补充信号：只看「方法定义处」出现 close/free/release/destroy。
# 718728b 那个双重释放的 bug 正是 KsgVertexBuffer.close()，
# 而它的函数体里既没有 memFree 之外的强特征，方法名才是线索。
# 匹配定义而非调用，避免把每一处 xxx.close() 都算进来。
LIFECYCLE_DEF = re.compile(
    r'\b(?:void|public|private|protected|static|final|\s)+'
    r'(?:[\w<>\[\],. ]+\s+)?'
    r'\b(?:close|free|release|destroy|dispose|cleanup|clear)\s*\(\s*\)\s*\{'
    r'|@Override\s+public\s+void\s+close\s*\('
)


def git(*args, check=True):
    """执行 git 命令并返回 stdout 文本。"""
    proc = subprocess.run(['git', *args], cwd=REPO_ROOT,
                          capture_output=True, text=True, errors='replace')
    if check and proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or f'git {" ".join(args)} 失败')
    return proc.stdout


def collect_files(base, staged):
    """返回 (文件列表, 比对描述)。"""
    if staged:
        return [f for f in git('diff', '--cached', '--name-only',
                                '--diff-filter=ACMR').splitlines() if f.strip()], '暂存区'
    try:
        git('rev-parse', '--verify', base, check=True)
    except RuntimeError:
        sys.exit(f'找不到基线 ref: {base}\n'
                 f'  试：python3 scripts/change-analyze.py --base origin/master')
    out = git('diff', '--name-only', '--diff-filter=ACMR', f'{base}...HEAD')
    return [f for f in out.splitlines() if f.strip()], f'{base}...HEAD'


def classify_file(path):
    """判断一个路径属于哪一类，返回 (module, kind)。

    module 恒为 modules/<name> / external/<name> / (其他) 之一，
    不再用前两段路径硬切，避免文档目录被当成模块。
    """
    parts = Path(path).parts
    if len(parts) >= 2 and parts[0] == 'modules':
        module = f'modules/{parts[1]}'
    elif len(parts) >= 2 and parts[0] == 'external':
        module = f'external/{parts[1]}'
    else:
        module = '(其他)'

    name = parts[-1] if parts else path
    if name in ('build.gradle', 'settings.gradle', 'gradle.properties'):
        return module, 'build'
    if parts and parts[0] == 'external':
        if 'native' in path or name.endswith(('.so', '.dll', '.dylib')):
            return module, 'native'
        return module, 'external'
    if '/src/test/' in path:
        return module, 'test'
    suffix = Path(name).suffix.lower()
    if suffix == '.java':
        return module, 'java'
    if suffix in {'.glsl', '.vsh', '.fsh', '.shader', '.frag', '.vert'}:
        return module, 'shader'
    if '/resources/' in path or suffix == '.json':
        return module, 'resource'
    return module, 'other'


def scan_risks(files):
    """对改动文件做关键词扫描，返回 {画像: [命中文件]}。

    刻意不扫纯资源文件（模型、datapack 内容、图标）：
    否则「改动堆里恰好有 alpha 这个字」就会报成 P-PIXEL 命中。

    P-STATE 额外做一次「生命周期方法定义」匹配（见 LIFECYCLE_DEF），
    因为 718728b 那个双重释放的 bug 线索只出现在 close() 的方法名上。
    """
    hits = {key: [] for key in RISK_PATTERNS}
    compiled = {k: re.compile(v) for k, v in RISK_PATTERNS.items()}
    for rel in files:
        p = REPO_ROOT / rel
        if not p.is_file() or p.suffix.lower() not in SCAN_SUFFIXES:
            continue
        try:
            text = p.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        for key, regex in compiled.items():
            if regex.search(text):
                hits[key].append(rel)
        if not hits['P-STATE'] or rel not in hits['P-STATE']:
            if LIFECYCLE_DEF.search(text):
                if rel not in hits['P-STATE']:
                    hits['P-STATE'].append(rel)
    return hits


def find_untouched_tests(java_files, changed):
    """找同目录下、但本次未一并修改的已有测试 —— 提醒确认是否需要重跑。"""
    changed_set = set(changed)
    found = []
    seen = set()
    for rel in java_files:
        d = (REPO_ROOT / rel).parent
        if not d.is_dir():
            continue
        for t in sorted(d.glob('*Test.java')):
            rel_t = str(t.relative_to(REPO_ROOT)).replace('\\', '/')
            if rel_t in changed_set or rel_t in seen:
                continue
            seen.add(rel_t)
            found.append(rel_t)
    return found


def build_report(base, staged, as_json):
    files, diff_desc = collect_files(base, staged)
    if not files:
        msg = f'无变更（对比 {diff_desc}）'
        print(json.dumps({'base': diff_desc, 'files': 0}) if as_json else msg)
        return 0

    modules = []
    counts = dict.fromkeys(('java', 'test', 'shader', 'resource', 'build', 'native', 'mixin'), 0)
    java_files = []
    for rel in files:
        module, kind = classify_file(rel)
        if module not in modules:
            modules.append(module)
        counts[kind] = counts.get(kind, 0) + 1
        if kind == 'java':
            java_files.append(rel)
            p = REPO_ROOT / rel
            try:
                if MIXIN_HINT.search(p.read_text(encoding='utf-8', errors='replace')):
                    counts['mixin'] += 1
            except OSError:
                pass

    hits = scan_risks(files)
    relevant = find_untouched_tests(java_files, files)

    if as_json:
        print(json.dumps({
            'base': diff_desc,
            'files': len(files),
            'modules': modules,
            'counts': counts,
            'risk': {k: len(v) for k, v in hits.items()},
            'riskFiles': hits,
            'relevantTests': relevant,
        }, ensure_ascii=False, indent=2))
        return 0

    bar = '═' * 60
    print(bar)
    print(f' 变更影响面草稿  ·  对比 {diff_desc}  ·  {len(files)} 个文件')
    print(bar)
    print('\n▸ 受影响模块')
    for m in modules:
        print(f'    {m}')

    print('\n▸ 变更构成')
    print(f'    源码 java={counts["java"]:<5} 测试={counts["test"]:<5} '
          f'着色器={counts["shader"]:<4} 资源={counts["resource"]:<5} '
          f'构建={counts["build"]:<3} 原生={counts["native"]:<3} mixin={counts["mixin"]}')
    if counts['native']:
        print('\n    ⚠ 存在原生相关改动 —— 必须回答平台/架构矩阵，见 P-NATIVE')
    if counts['mixin']:
        print('    ⚠ 存在 mixin / 注入改动 —— 必须声明可能的冲突方，见 P-XMOD')

    total_hits = sum(len(v) for v in hits.values())
    print('\n▸ 风险画像命中（关键词命中文件数，仅供起点，不是判定）')
    if total_hits == 0:
        print('    无关键词命中。')
    else:
        for key, matched in hits.items():
            if matched:
                print(f'    {key:<9} {len(matched):>3} 个文件   {RISK_NAMES[key]}')
        print('\n    命中文件（供复核）：')
        rows = [(key, f) for key in ('P-STATE', 'P-ORDER', 'P-PIXEL', 'P-XMOD',
                                     'P-NATIVE', 'P-SAVE') for f in hits[key]]
        limit = 18
        for key, f in rows[:limit]:
            print(f'      {key:<9} {f}')
        if len(rows) > limit:
            # 计数必须写清「还有多少条命中」，而不是留一个看不懂的省略号。
            remaining = rows[limit:]
            by_profile = {}
            for key, _ in remaining:
                by_profile[key] = by_profile.get(key, 0) + 1
            detail = '，'.join(f'{k} 还有 {v}' for k, v in by_profile.items())
            print(f'      … 另有 {len(remaining)} 条命中未显示（{detail}）')
            print(f'        看全部：加 --json')
    print('\n    ⚠ 命中数为 0 不代表不命中。关键词匹配会漏、也会误报。')
    print('      6 条画像必须逐条判定「命中 / 不命中+理由」。')
    print('      见 doc/process/CHANGE-IMPACT-PROCESS.md')

    if relevant:
        print('\n▸ 同目录已有测试（本次未改动 —— 需确认是否要重跑）')
        for t in relevant[:12]:
            print(f'    {t}')
        if len(relevant) > 12:
            print(f'    … 还有 {len(relevant) - 12} 个')

    print('\n▸ 脚本【识别不出】的部分（必须人工判断）')
    print('    · 语义耦合：改了一个方法但语义影响超出其所在目录')
    print('    · 反向依赖：被改 API 的所有下游使用点')
    print('    · 运行时才显现：特定 tick / 特定平台 / 与其他 mod 共存')
    print('\n下一步：读 doc/process/RISK-PROFILES.md 逐条判定，然后写测试策略。')
    print(bar)
    return 0


def main():
    ap = argparse.ArgumentParser(
        description='生成变更影响面草稿（PR 三件套的第一件）',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='示例：\n'
               '  python3 change_analyze.py\n'
               '  python3 change_analyze.py --base origin/master\n'
               '  python3 change_analyze.py --staged\n'
               '  python3 change_analyze.py --json\n')
    ap.add_argument('--base', default='upstream/master',
                    help='比对基线，默认 upstream/master')
    ap.add_argument('--staged', action='store_true',
                    help='只看暂存区')
    ap.add_argument('--json', action='store_true',
                    help='输出 JSON（供 agent / CI 消费）')
    ap.add_argument('--repo', default=None,
                    help='目标仓库根路径。不指定时从当前目录向上找 git 根')
    args = ap.parse_args()
    global REPO_ROOT
    REPO_ROOT = _resolve_repo_root(args.repo)
    try:
        return build_report(args.base, args.staged, args.json)
    except RuntimeError as exc:
        sys.exit(str(exc))


if __name__ == '__main__':
    sys.exit(main())
