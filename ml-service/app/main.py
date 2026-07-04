from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse

from app.blackbox import process_image
from app.models import AnalysisResult

app = FastAPI(title="ML Service", version="1.0.0")

@app.get("/health")
async def health():
    """Healthcheck endpoint used by Ktor to verify ML is online."""
    return {"status": "ready", "models_loaded": True}

@app.post("/analyze", response_model=AnalysisResult)
async def analyze(file: UploadFile = File(...)):
    """Receives image bytes from Ktor and runs ML inference."""
    try:
        file_bytes = await file.read()
        filename = file.filename or "unknown"
        
        # Вызываем process_image, передавая sample_id как имя файла
        result = await process_image(sample_id=filename, file_bytes=file_bytes, filename=filename)
        return result
    except Exception as e:
        return JSONResponse(
            status_code=500,
            content={"detail": f"Inference failed: {str(e)}"}
        )