#!/usr/bin/env python3
"""数据集全量运行的进度播报（只读，运行中随时可执行，不触发任何 VLM 调用）。

用法：
    progress_report.py <run目录 或 runs目录> <总图片数> <总试卷数> [--plain]

默认输出 Markdown 表格，固定五项口径：
    1 图片进度 = 已处理图片 / 总图片数（百分比）
    2 试卷进度 = 已处理试卷数 / 总试卷数（百分比）
    3 准确率   = (有页码成功擦除 + 无页码) / 已处理图片数
    4 人工审核图片数
    5 转人工 manual_review 原因分布

`--plain` 输出旧版单行 tab 分隔格式，供既有脚本消费。
"""
import datetime
import glob
import json
import os
import sys


def resolve_run_dir(path: str) -> str:
    """参数既可以是 run 目录本身，也可以是 runs 目录；后者取最新的一次 run。"""
    if os.path.exists(os.path.join(path, "_progress.ndjson")) or os.path.exists(os.path.join(path, "run.json")):
        return path
    candidates = [d for d in glob.glob(os.path.join(path, "*/")) if os.path.isdir(d)]
    if not candidates:
        return path
    return max(candidates, key=os.path.getmtime)


def load_run_meta(run_dir: str, total_pages: int, total_exams: int) -> dict:
    meta = {"model": "?", "planned_pages": total_pages, "planned_exams": total_exams, "finished": False}
    run_json = os.path.join(run_dir, "run.json")
    if not os.path.exists(run_json):
        return meta
    with open(run_json, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    meta["model"] = data.get("model", "?")
    meta["planned_pages"] = data.get("planned_page_count", total_pages)
    meta["planned_exams"] = data.get("planned_exam_count", total_exams)
    meta["finished"] = bool(data.get("completed_at")) or data.get("status") == "completed"
    return meta


def collect(run_dir: str) -> dict:
    """按页终态统计：safe_to_erase（有页码成功擦除）/ no_pagenum（正确无页码）/ manual_review。"""
    stats = {"safe": 0, "no_pagenum": 0, "manual": 0, "exams": 0, "last_ts": 0, "reasons": {}}
    progress_file = os.path.join(run_dir, "_progress.ndjson")
    if not os.path.exists(progress_file):
        return stats
    page_events = {}
    fallback_events = {}
    processed_exams = set()
    with open(progress_file, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            event = json.loads(line)
            stats["last_ts"] = max(stats["last_ts"], event.get("timestamp_ms", 0))
            if event.get("stage") == "page":
                key = (event.get("exam_id"), event.get("page_id"))
                if key[0] and key[1] and event.get("status") in {"safe_to_erase", "no_pagenum", "manual_review"}:
                    page_events[key] = event
            elif event.get("stage") == "model_fallback":
                key = (event.get("exam_id"), event.get("page_id"))
                if key[0] and key[1] and event.get("status") in {"accepted", "not_accepted"}:
                    fallback_events[key] = event
            elif event.get("stage") == "exam" and event.get("status") == "processed":
                exam_id = event.get("exam_id")
                if exam_id:
                    processed_exams.add(exam_id)

    # MAX accepted replaces the same page's Flash manual_review; rejected keeps Flash final state.
    for key, event in fallback_events.items():
        if event.get("status") == "accepted":
            reason = event.get("reason", "")
            page_events[key] = {
                "status": "safe_to_erase" if "fallback_status=safe_to_erase" in reason else "no_pagenum",
                "reason": reason,
            }

    for event in page_events.values():
        status = event.get("status")
        if status == "safe_to_erase":
            stats["safe"] += 1
        elif status == "no_pagenum":
            stats["no_pagenum"] += 1
        elif status == "manual_review":
            stats["manual"] += 1
            reason = event.get("reason") or "unknown"
            stats["reasons"][reason] = stats["reasons"].get(reason, 0) + 1
    stats["exams"] = len(processed_exams)
    return stats


def percentage(numerator: int, denominator: int) -> float:
    return numerator * 100.0 / denominator if denominator else 0.0


def main() -> None:
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(2)
    plain = "--plain" in sys.argv
    run_dir = resolve_run_dir(sys.argv[1])
    total_pages = int(sys.argv[2])
    total_exams = int(sys.argv[3])

    meta = load_run_meta(run_dir, total_pages, total_exams)
    stats = collect(run_dir)
    processed = stats["safe"] + stats["no_pagenum"] + stats["manual"]
    planned_pages = meta["planned_pages"]
    planned_exams = meta["planned_exams"]
    image_pct = percentage(processed, planned_pages)
    exam_pct = percentage(stats["exams"], planned_exams)
    accuracy = percentage(stats["safe"] + stats["no_pagenum"], processed)
    clock = datetime.datetime.fromtimestamp(stats["last_ts"] / 1000).strftime("%H:%M:%S") if stats["last_ts"] else "--:--:--"

    if plain:
        print(
            f"{clock}|{meta['model']}|{processed}/{planned_pages}|{image_pct:.1f}%|"
            f"{stats['exams']}/{planned_exams}|{exam_pct:.1f}%|{accuracy:.1f}%|"
            f"{stats['manual']}|{stats['safe']}|{stats['no_pagenum']}"
        )
        return

    state = "运行已结束" if meta["finished"] else "运行中"
    print(f"[{clock}] {state} · {meta['model']}")
    print()
    print("| 指标 | 数值 |")
    print("| --- | --- |")
    print(f"| 1 图片进度 | {processed}/{planned_pages}（{image_pct:.1f}%） |")
    print(f"| 2 试卷进度 | {stats['exams']}/{planned_exams}（{exam_pct:.1f}%） |")
    print(f"| 3 准确率 | {accuracy:.1f}% |")
    print(f"| 4 人工审核图片数 | {stats['manual']} |")
    print()
    print("| 5 转人工 manual_review 原因 | 数量 |")
    print("| --- | --- |")
    if stats["reasons"]:
        for reason, count in sorted(stats["reasons"].items(), key=lambda item: (-item[1], item[0])):
            print(f"| {reason} | {count} |")
    else:
        print("| 暂无 | 0 |")


if __name__ == "__main__":
    main()
