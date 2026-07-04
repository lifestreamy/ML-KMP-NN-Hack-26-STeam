import asyncio

from app.models import AnalysisResult, Defect, PhaseInfo

USE_FAKE_MODEL = True


async def process_image(sample_id: str, file_bytes: bytes) -> AnalysisResult:
    if USE_FAKE_MODEL:
        await asyncio.sleep(3)
        return AnalysisResult(
            sample_id=sample_id,
            ore_class="talcose",
            talkc_pct=14.2,
            phases={
                "ordinary_intergrowths": PhaseInfo(area_pct=24.1, color="#00FF00"),
                "fine_intergrowths": PhaseInfo(area_pct=61.7, color="#FF0000"),
            },
            defects=[
                Defect(type="crack", area_px=1200, bbox=[120, 45, 180, 90]),
            ],
        )
    raise NotImplementedError("Real ML pipeline will be injected here")
