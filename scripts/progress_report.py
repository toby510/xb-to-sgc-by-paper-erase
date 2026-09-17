#!/usr/bin/env python3
"""计算数据集5运行进度四项指标，从最新 run 目录的 _progress.ndjson 读取。

用法: progress_report.py <runs-dir> <总图片数> <总试卷数>
输出: 单行 tab 分隔: 时间|模型|已处理图片/总图片|图片进度%|已处理试卷/总试卷|试卷进度%|准确率%|人工审核数|有页码成功擦除|正确无页码
"""
import json
import os
import sys
import glob


def main() -> None:
    runs_dir = sys.argv[1]
    total_pages = int(sys.argv[2])
    total_exams = int(sys.argv[3])

    run_dirs = sorted(glob.glob(os.path.join(runs_dir, "*/")))
    if not run_dirs:
        print("NO_RUN")
        return
    run_dir = run_dirs[-1]

    progress_file = os.path.join(run_dir, "_progress.ndjson")
    if not os.path.exists(progress_file):
        print("NO_PROGRESS")
        return

    model = "?"
    planned_pages = total_pages
    planned_exams = total_exams
    run_json = os.path.join(run_dir, "run.json")
    if os.path.exists(run_json):
        with open(run_json, "r", encoding="utf-8") as f:
            rj = json.load(f)
        model = rj.get("model", "?")
        planned_pages = rj.get("planned_page_count", total_pages)
        planned_exams = rj.get("planned_exam_count", total_exams)

    safe_to_erase = 0
    no_pagenum = 0
    manual_review = 0
    page_started = 0
    exams_processed = 0
    last_ts = 0

    with open(progress_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            stage = o.get("stage")
            status = o.get("status")
            ts = o.get("timestamp_ms", 0)
            if ts > last_ts:
                last_ts = ts
            if stage == "page":
                if status == "started":
                    page_started += 1
                elif status == "safe_to_erase":
                    safe_to_erase += 1
                elif status == "no_pagenum":
                    no_pagenum += 1
                elif status == "manual_review":
                    manual_review += 1
            elif stage == "exam" and status == "processed":
                exams_processed += 1

    processed = safe_to_erase + no_pagenum + manual_review
    if processed > 0:
        img_pct = processed * 100.0 / planned_pages
        acc = (safe_to_erase + no_pagenum) * 100.0 / processed
    else:
        img_pct = 0.0
        acc = 0.0
    exam_pct = exams_processed * 100.0 / planned_exams if planned_exams else 0.0

    import datetime

    ts_str = datetime.datetime.fromtimestamp(last_ts / 1000).strftime("%H:%M:%S")
    print(
        f"{ts_str}|{model}|{processed}/{planned_pages}|{img_pct:.1f}%|"
        f"{exams_processed}/{planned_exams}|{exam_pct:.1f}%|{acc:.1f}%|"
        f"{manual_review}|{safe_to_erase}|{no_pagenum}"
    )


if __name__ == "__main__":
    main()
