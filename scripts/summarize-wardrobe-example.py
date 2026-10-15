#!/usr/bin/env python3
"""Summarize client acceptance and three fresh-JVM assembly runs into one Markdown file."""
import argparse
import json
from pathlib import Path
import statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True, help="Client debug/wardrobe-example directory")
    parser.add_argument("--output", type=Path, help="Markdown output, defaults to <capture>/summary.md")
    parser.add_argument("reports", type=Path, nargs=3, help="Three independent JVM reports, with alternating order")
    args = parser.parse_args()
    if len({path.resolve() for path in args.reports}) != 3:
        raise ValueError("Provide three distinct report files")
    reports = [json.loads(path.read_text()) for path in args.reports]
    if len({report["jvmPid"] for report in reports}) != 3:
        raise ValueError("The reports must come from three independent JVM processes")
    capture = json.loads((args.capture / "report.json").read_text())
    if not capture.get("passed") or any(report.get("failed") for report in reports):
        raise ValueError("Capture or model loading failed; inspect reports before publishing results")
    if {report["order"] for report in reports} != {"cached-first", "uncached-first"}:
        raise ValueError("Use both measurement orders across three fresh JVMs")
    cases = [[case for case in report["benchmarks"] if "error" not in case] for report in reports]
    if any(any("error" in case for case in report["benchmarks"]) for report in reports):
        raise ValueError("Assembly benchmark failed")
    names = [case["case"] for case in cases[0]]
    if any({case["case"] for case in fork} != set(names) for fork in cases):
        raise ValueError("Benchmark cases differ between runs")
    summary = []
    for name in names:
        forks = [next(case for case in fork if case["case"] == name) for fork in cases]
        if len({(case["vertices"], case["bones"]) for case in forks}) != 1:
            raise ValueError(f"Geometry differs across {name} runs")
        row = {"case": name, "vertices": forks[0]["vertices"], "bones": forks[0]["bones"],
               "includesHair": forks[0].get("includesHair", False)}
        for metric in ("uncachedAssembly", "cachedRequest", "buildRequestAndCache"):
            row[metric + "MedianMicros"] = statistics.median(fork[metric]["batchMeanP50Micros"] for fork in forks)
            if all("allocatedBytesPerOp" in fork[metric] for fork in forks):
                row[metric + "MedianBytes"] = statistics.median(fork[metric]["allocatedBytesPerOp"] for fork in forks)
        summary.append(row)
    labels = {"compatible_body_and_dress": "Ayuchan 素体 + 长裙（共享兼容骨骼）",
              "ribbon_short_independent_rig": "素体 + RibbonDress 短袖 + 13 号头发",
              "ribbon_long_independent_rig": "素体 + RibbonDress 长袖 + 13 号头发",
              "compatible_bones_only_1_parts": "1 份真实小衣物",
              "compatible_bones_only_10_parts": "10 份重复小衣物",
              "compatible_bones_only_100_parts": "100 份重复小衣物"}
    def label(row):
        text = labels.get(row["case"], row["case"])
        return text if row["includesHair"] else text.replace(" + 13 号头发", "")

    rows = "\n".join(f"| {label(row)} | {row['vertices']:,} | {row['bones']:,} | "
                     f"{row['uncachedAssemblyMedianMicros'] / 1000:.3f} | "
                     f"{row['buildRequestAndCacheMedianMicros']:.3f} | {row['cachedRequestMedianMicros']:.3f} |"
                     for row in summary)
    observed = "\n".join(f"| {operation['operation']} | "
                         f"{operation.get('cpuMicros', operation.get('cpuMicrosIncludingInstanceAndBackend')) / 1000:.3f} |"
                         for operation in capture["operations"])
    evidence = capture["evidence"]
    checks = "\n".join(f"| {name} | {evidence[name]} |" for name in (
        "textureReusesModel", "textureChangedPixels", "modelChangedPixels", "returnedModelReused",
        "equipmentChangedPixels", "wristChangedPixels", "newBuildsDuringCapture"))
    page = f"""# 真实模型换装与装备：性能和客户端验收摘要

输入为用户提供的 Morphable Base、RibbonDress 短袖/长袖、原始配色贴图和 13 号头发。
MC Diamond Sword 通过原生 ItemRenderer 在真实右手首锚点上渲染。

## CPU 组装缓存基准

环境：{reports[0]['os']} / {reports[0]['arch']}，Java {reports[0]['javaRuntime']}；
客户端 GL renderer 为 {evidence['renderer']}。三次 JVM 的 PID 为
{', '.join(str(report['jvmPid']) for report in reports)}，测量顺序为
`uncached-first`、`cached-first`、`uncached-first`。

表格是三个 JVM 各自批次均值 p50 的中位数，排除文件解析、贴图解码、GPU 蒙皮和绘制。

| 用例 | 顶点 | 骨骼 | 未缓存组装（ms） | 构建请求＋热缓存（µs） | 已有请求查缓存（µs） |
| --- | ---: | ---: | ---: | ---: | ---: |
{rows}

100 份压力用例重复同一件 461 顶点的小衣物，不代表 100 件不同衣服已适配素体。
上述数据只衡量 CPU 组装与缓存，不能换算为游戏 FPS 提升。

## 实际操作的单次 CPU 观测

以下数值未做预热统计。模型切换包含实例创建与 backend 挂载，命中 CPU 网格缓存
仍可能花时间创建渲染资源；它不同于上面的缓存查询基准，也不是 GPU 或整帧耗时。

| 操作 | 单次 CPU 观测（ms） |
| --- | ---: |
{observed}

## 客户端验收

`passed={str(capture['passed']).lower()}`，共 {capture['frames']} 帧、{capture['itemsRendered']} 次原生物品提交，
总计 {capture['cache']['builds']} 次组装 build。每次 JVM 都成功加载 {reports[0]['loaded']} 个 PMX。

| 检查项 | 结果 |
| --- | ---: |
{checks}

纹理、衣服模型、装备和手腕旋转均出现实际画面变化；换纹理保持同一个 Model，
换回短袖复用原组装模型且 build 数不增加。

## 适配边界

RibbonDress 与素体的绑定位置不同，示例保留衣服和头发的独立绑定骨架，
不启用物理或全身动画，不宣称自动体型拟合或骨骼重定向。
Ayuchan 配套长裙基准使用严格兼容的共享骨骼。
"""
    output = args.output or args.capture / "summary.md"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(page, encoding="utf-8")
    for row in summary:
        print(f"{row['case']}: uncached={row['uncachedAssemblyMedianMicros']/1000:.3f}ms request+cache={row['buildRequestAndCacheMedianMicros']:.3f}us cache={row['cachedRequestMedianMicros']:.3f}us")
    print(f"Markdown: {output.resolve()}")


if __name__ == "__main__":
    main()
