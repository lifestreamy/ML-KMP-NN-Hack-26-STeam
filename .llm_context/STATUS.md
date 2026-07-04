# Project Status

## Active Task
Phase 8 — Ktor & Python Real Integration & Reliability (Backpressure)

## Phase
Phase 8 — Backend Integration

## Completed
- [x] Phase 1-4: Setup, Models, UI shell, Local Fake
- [x] Phase 5: Python ML Service Skeleton
- [x] Phase 6: Ktor Gateway (SSE, Traffic Lights)
- [x] Phase 7: UI FilePicker (FileKit 0.14.2) & Image Caching (Coil 3.5.0). Web selection works flawlessly.

## Pending (Phase 8 - Reliability & Integration)
- [ ] **Dynamic File Metadata**: Update `NetworkAnalysisRepository.kt` to send real file names and MIME types (from `PlatformFile.name`) instead of hardcoded `slide_0.tiff`.
- [ ] **Ktor Logging & Analytics**: Add verbose logging to `Application.kt` and `TaskQueueManager.kt`. Print received files, client connections, and JVM memory stats (e.g., `Runtime.getRuntime().freeMemory()`).
- [ ] **Ktor Temp File Cleanup**: Ensure temporary files (`java.io.tmpdir`) uploaded via multipart are strictly deleted (`.delete()`) after processing.
- [ ] **Python OOM Protection (Backpressure)**: Implement concurrency limits directly in FastAPI (`blackbox.py` / `main.py`). Use an `asyncio.Semaphore(1)` or return `429 Too Many Requests` if the ML model is currently busy. Python must protect its own GPU/RAM.
- [ ] **Ktor -> Python Queue**: Connect Ktor's `TaskQueueManager` to actually send HTTP POST requests to `http://ml-service:8000/analyze` sequentially, respecting Python's readiness.