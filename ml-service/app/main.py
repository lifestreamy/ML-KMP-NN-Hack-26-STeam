from fastapi import FastAPI, File, UploadFile

from app.blackbox import process_image
from app.models import AnalysisResult

app = FastAPI(title="ML Service", version="1.0.0")


@app.get("/health")
async def health():
    return {"status": "ready", "models_loaded": True}


@app.post("/analyze", response_model=AnalysisResult)
async def analyze(file: UploadFile = File(...)):
    file_bytes = await file.read()
    result = await process_image(file.filename or "unknown", file_bytes)
    return result
