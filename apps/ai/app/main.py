from fastapi import FastAPI

from app.schemas import HealthResponse

app = FastAPI(title="Capstone AI Service")


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")
