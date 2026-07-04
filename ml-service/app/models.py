from pydantic import BaseModel


class PhaseInfo(BaseModel):
    area_pct: float
    color: str


class Defect(BaseModel):
    type: str
    area_px: int
    bbox: list[int]


class AnalysisResult(BaseModel):
    sample_id: str
    ore_class: str
    talkc_pct: float
    phases: dict[str, PhaseInfo] = {}
    defects: list[Defect] = []
