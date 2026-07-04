import asyncio
import time
from fastapi import HTTPException
from app.models import AnalysisResult, Defect, PhaseInfo

USE_FAKE_MODEL = True

# СЕМАФОР: Разрешаем обрабатывать только ОДНУ картинку одновременно. 
# Если поставить 2, то будет 2 параллельно (если позволяет GPU).
# Для хакатона ставим 1 — это 100% гарантия от OOM.
ml_semaphore = asyncio.Semaphore(1)

async def process_image(sample_id: str, file_bytes: bytes, filename: str = "unknown") -> AnalysisResult:
    size_mb = len(file_bytes) / (1024 * 1024)
    print(f"\n[ML-PIPELINE] Task '{sample_id}' arrived. File: {filename} ({size_mb:.2f} MB)")
    
    # Пытаемся занять семафор. Если он занят - ждем своей очереди.
    print(f"[ML-PIPELINE] Task '{sample_id}' is waiting for ML resources...")
    
    async with ml_semaphore:
        print(f"[ML-PIPELINE] ML Resources acquired! Processing '{sample_id}'...")
        
        if USE_FAKE_MODEL:
            start_time = time.time()
            # Имитация работы тяжелой YOLO/SAM модели
            await asyncio.sleep(3)
            print(f"[ML-PIPELINE] Inference complete for '{sample_id}' in {time.time() - start_time:.2f}s!")
            
            return AnalysisResult(
                sample_id=sample_id,
                ore_class="talcose",
                talkc_pct=14.2,
                phases={
                    "ordinary_intergrowths": PhaseInfo(
                        area_pct=24.1, 
                        color="#00FF00", 
                        polygons=[[[10, 20], [150, 20], [150, 250], [10, 250]]]
                    ),
                    "fine_intergrowths": PhaseInfo(
                        area_pct=61.7, 
                        color="#FF0000",
                        polygons=[[[200, 200], [300, 200], [250, 300]]]
                    ),
                },
                defects=[
                    Defect(type="crack", area_px=1200, bbox=[120, 45, 180, 90]),
                ]
            )

        raise NotImplementedError("Real ML pipeline will be injected here")