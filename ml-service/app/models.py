from typing import Optional

from pydantic import BaseModel, ConfigDict


class Stage1Details(BaseModel):
    model_config = ConfigDict(extra='ignore')

    pct_sulfide: float
    pct_potential_talc: float
    pct_background: float
    pct_inclusions_in_talc: float
    pct_final_zone: float
    stage1_pred: str


class AnalysisResult(BaseModel):
    model_config = ConfigDict(extra='ignore')

    sample: str
    final_label: str
    stage1_details: Stage1Details
    stage2_pred: Optional[str] = None
    stage2_prob_trudnie: Optional[float] = None
