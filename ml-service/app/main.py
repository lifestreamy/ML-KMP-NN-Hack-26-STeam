import os
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app.blackbox import process_image

app = FastAPI(title="ML Pipeline API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

current_dir = os.path.dirname(os.path.abspath(__file__))
outputs_dir = os.path.join(os.path.dirname(current_dir), "outputs")
os.makedirs(outputs_dir, exist_ok=True)
app.mount("/outputs", StaticFiles(directory=outputs_dir), name="outputs")

@app.get("/health")
def health_check():
    return {"status": "ok"}

@app.post("/analyze")
async def analyze_endpoint(
        file: UploadFile = File(...),
        sample_id: str = Form(None)
):
    try:
        file_bytes = await file.read()

        if not sample_id:
            sample_id = file.filename.split("_")[0] if "_" in file.filename else "unknown"

        result = await process_image(sample_id, file_bytes, file.filename)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))