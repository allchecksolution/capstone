from fastapi import FastAPI

from app.schemas import HealthResponse
from app.transcribe import router as transcribe_router

app = FastAPI(title="Capstone AI Service")
app.include_router(transcribe_router)


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")
