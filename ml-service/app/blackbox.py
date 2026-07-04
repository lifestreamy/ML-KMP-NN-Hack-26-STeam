import asyncio
import time
from app.models import AnalysisResult, Defect, PhaseInfo

USE_FAKE_MODEL = True

async def process_image(sample_id: str, file_bytes: bytes) -> AnalysisResult:
    size_mb = len(file_bytes) / (1024 * 1024)
    print(f"\n[ML-PIPELINE] Received task '{sample_id}' (Size: {size_mb:.2f} MB)")
    
    if USE_FAKE_MODEL:
        print(f"[ML-PIPELINE] USE_FAKE_MODEL=True. Simulating 3s inference...")
        start_time = time.time()
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
                    polygons=[[[10, 20], [150, 20], [150, 250], [10, 250]]] # Fake rect polygon
                ),
                "fine_intergrowths": PhaseInfo(
                    area_pct=61.7, 
                    color="#FF0000",
                    polygons=[[[200, 200], [300, 200], [250, 300]]] # Fake triangle polygon
                ),
            },
            defects=[
                Defect(type="crack", area_px=1200, bbox=[120, 45, 180, 90]),
            ]
        )

    raise NotImplementedError("Real ML pipeline will be injected here")