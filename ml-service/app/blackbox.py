import asyncio
import time
import random
from app.models import AnalysisResult, Defect, PhaseInfo

USE_FAKE_MODEL = True

# СЕМАФОР: Защита GPU/RAM от OOM (одновременная обработка 1 задачи)
ml_semaphore = asyncio.Semaphore(1)

async def process_image(sample_id: str, file_bytes: bytes, filename: str = "unknown") -> AnalysisResult:
    size_mb = len(file_bytes) / (1024 * 1024)
    print(f"\n[ML-PIPELINE] Task '{sample_id}' arrived. File: {filename} ({size_mb:.2f} MB)")
    print(f"[ML-PIPELINE] Task '{sample_id}' is waiting for ML resources...")

    async with ml_semaphore:
        print(f"[ML-PIPELINE] ML Resources acquired! Processing '{sample_id}'...")

        if USE_FAKE_MODEL:
            start_time = time.time()
            await asyncio.sleep(3) # Имитация работы модели

            # Локальная генерация только для проверки сквозной передачи данных
            pct = round(random.uniform(10.0, 30.0), 1)

            result = AnalysisResult(
                sample_id=sample_id,
                ore_class="talcose" if pct < 20 else "sulfide",
                talkc_pct=pct,
                phases={
                    "ordinary": PhaseInfo(area_pct=round(100 - pct, 1), color="#00FF00", polygons=[[[10, 20], [150, 250], [10, 250]]]),
                },
                defects=[Defect(type="crack", area_px=random.randint(1000, 5000), bbox=[120, 45, 180, 90])]
            )

            print(f"[ML-PIPELINE] Inference complete in {time.time() - start_time:.2f}s! Result: {result.ore_class} ({result.talkc_pct}%)")
            return result

        # ==========================================
        # ВСТАВИТЬ РЕАЛЬНЫЙ ML-ИНФЕРЕНС ЗДЕСЬ
        # ==========================================
        raise NotImplementedError("Real ML pipeline will be injected here")