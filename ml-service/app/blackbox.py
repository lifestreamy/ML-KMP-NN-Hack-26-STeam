import asyncio
import os
import random
import time
from pathlib import Path

from app.models import AnalysisResult, Stage1Details

# Пытаемся импортировать реальный ML
try:
    from app.backend_app import OreCascadePipeline

    current_dir = Path(str(__file__)).parent
    model_path = current_dir.parent.parent / "solutions" / "ore-cascade" / "model" / "cnn_two_class_final.pth"

    pipeline = OreCascadePipeline(model_path=str(model_path.resolve()))
    USE_FAKE_MODEL = False
    print("[ML-PIPELINE] REAL ML PIPELINE LOADED SUCCESSFULLY!")
except Exception as e:
    print(f"[ML-PIPELINE] Failed to load real ML (using fake instead): {e}")
    USE_FAKE_MODEL = True
    pipeline = None

ml_semaphore = asyncio.Semaphore(1)
os.makedirs("outputs", exist_ok=True)


async def process_image(sample_id: str, file_bytes: bytes, filename: str = "unknown") -> AnalysisResult:
    size_mb = len(file_bytes) / (1024 * 1024)
    print(f"\n[ML-PIPELINE] Task '{sample_id}' arrived. File: {filename} ({size_mb:.2f} MB)")

    async with ml_semaphore:
        print(f"[ML-PIPELINE] ML Resources acquired! Processing '{sample_id}'...")
        start_time = time.time()

        if USE_FAKE_MODEL:
            await asyncio.sleep(2)
            pct = round(random.uniform(10.0, 30.0), 1)
            ans = AnalysisResult(
                sample=sample_id,
                final_label="otalkovanie" if pct < 20 else "ryadovie",
                stage1_details=Stage1Details(
                    pct_sulfide=random.uniform(5, 15),
                    pct_potential_talc=pct,
                    pct_background=random.uniform(20, 50),
                    pct_inclusions_in_talc=random.uniform(1, 5),
                    pct_final_zone=random.uniform(50, 90),
                    stage1_pred="otalkovanie"
                ),
                stage2_pred="ryadovie",
                stage2_prob_trudnie=0.15
            )
            # фейковые серые картинки-заглушки для UI
            from PIL import Image
            img = Image.new('RGB', (400, 300), color=(100, 100, 100))
            img.save(f"outputs/{sample_id}_zones.png", "PNG")
            img.save(f"outputs/{sample_id}_density.png", "PNG")

        else:
            raw_result = await asyncio.to_thread(
                pipeline.process_with_visualization,
                file_bytes=file_bytes,
                sample_name=sample_id
            )

            # Сохраняем сгенерированные PNG картинки на диск
            with open(f"outputs/{sample_id}_zones.png", "wb") as f:
                f.write(raw_result["visualizations"]["zones_png"])
            with open(f"outputs/{sample_id}_density.png", "wb") as f:
                f.write(raw_result["visualizations"]["density_png"])

            res = raw_result["result"]
            ans = AnalysisResult(
                sample=res["sample"],
                final_label=res["final_label"],
                stage1_details=Stage1Details(**res["stage1_details"]),
                stage2_pred=res.get("stage2_pred"),
                stage2_prob_trudnie=res.get("stage2_prob_trudnie")
            )

        print(f"[ML-PIPELINE] Inference complete in {time.time() - start_time:.2f}s! Result: {ans.final_label}")
        return ans