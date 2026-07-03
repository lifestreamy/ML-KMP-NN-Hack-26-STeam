# API_CONTRACTS
> Version: 1.0

## Base Policy
- Все запросы от UI к Ktor Gateway должны содержать HTTP-заголовок: `X-Client-Id: <uuid>`

---

## 1. Ktor Gateway API (Внешний REST API для UI)
*Служит прослойкой между клиентом и ML. Управляет клиентами и очередью.*

### 1.1 `GET /api/queue/status`
Получение статуса очереди и лимитов для конкретного клиента.
* **Headers**: `X-Client-Id`
* **Response 200 OK**:
```json
{
  "global_queue_size": 15,
  "global_queue_limit": 50,
  "client_active_tasks": 2,
  "client_task_limit": 10
}
```

### 1.2 `POST /api/tasks`
Отправка пачки изображений в очередь на обработку.
* **Headers**: `X-Client-Id`
* **Content-Type**: `multipart/form-data`
* **Body**: `files` (Массив: `image/png`, `image/jpeg`, `image/tiff`)
* **Response 200 OK**:
```json
{
  "tasks": [ 
    { "task_id": "8f3b2a-11c", "filename": "slide1.tiff" } 
  ]
}
```
* **Response 429 Too Many Requests**: Возвращается, если превышен `global_queue_limit` или `client_task_limit`.
```json
{ "error": "Queue limit reached" }
```

### 1.3 `DELETE /api/tasks`
Отмена задач, находящихся в очереди.
* **Headers**: `X-Client-Id`
* **Content-Type**: `application/json`
* **Body**:
```json
{ "task_ids": ["8f3b2a-11c", "a2c4e5-99d"] }
```
* **Response 200 OK**: `{ "status": "cancelled" }`

### 1.4 `GET /api/tasks/stream`
Открывает SSE-соединение для получения обновлений по задачам текущего клиента.
* **Headers**: `X-Client-Id`
* **Content-Type**: `text/event-stream`
* **Events**:
  * Событие `update`: Изменение статуса задачи (встала в очередь, обрабатывается и т.д.)
  ```text
  event: update
  data: {"task_id": "8f3b2a-11c", "stage": "queued", "position_in_queue": 3}
  ```
  ```text
  event: update
  data: {"task_id": "8f3b2a-11c", "stage": "segmentation"}
  ```
  * Событие `result`: Успешное завершение. Тело содержит `AnalysisResult`.
  ```text
  event: result
  data: {"task_id": "8f3b2a-11c", "stage": "done", "result": { "ore_class": "talcose", "talkc_pct": 14.2, "phases": {...}, "defects": [...] }}
  ```
  * Событие `error`: Ошибка обработки.
  ```text
  event: error
  data: {"task_id": "8f3b2a-11c", "stage": "error", "message": "Failed to parse image"}
  ```

### 1.5 `POST /api/feedback`
Сохранение ручных корректировок маски в датасет.
* **Headers**: `X-Client-Id`
* **Content-Type**: `application/json`
* **Body**:
```json
{
  "task_id": "8f3b2a-11c",
  "corrected_mask_polygons": [ ... ],
  "expert_comment": "Это не тальк, это царапина"
}
```
* **Response 200 OK**: `{ "status": "saved_for_training" }`

---

## 2. Python ML FastAPI (Внутренний ML API)
*UI сюда не ходит. Доступен только из Ktor Gateway. FastAPI не знает про очереди, клиентов и SSE-стриминг клиенту. Он просто считает математику.*

### 2.1 `GET /health`
Проверка готовности ML-сервиса к работе (загружены ли веса моделей в память).
* **Response 200 OK**: `{ "status": "ready", "models_loaded": true }`
* **Response 503 Service Unavailable**: `{ "status": "loading_weights" }`

### 2.2 `POST /analyze`
Синхронный эндпоинт для инференса одного изображения. Ktor Gateway держит соединение открытым, пока Python не ответит.
* **Content-Type**: `multipart/form-data`
* **Body**: `file` (Одна картинка)
* **Response 200 OK**: Возвращает `AnalysisResult` (структуру JSON см. ниже).
* **Response 422 Unprocessable Entity**: Ошибка валидации файла.
* **Response 500 Internal Server Error**: Внутренняя ошибка ML пайплайна (OOM, CUDA error).

> **Опционально (Если ML-инженер реализует внутренний прогресс):**
> FastAPI может реализовать свой рудиментарный SSE эндпоинт `GET /analyze/progress/{internal_task_id}`, чтобы Ktor Gateway мог читать текущую стадию ML-пайплайна (`preprocessing`, `segmentation`, `classification`) и проксировать её клиенту. Если ML-инженер это не реализует, Ktor просто отдает клиенту стадию `processing` до получения финального ответа.

---

## 3. Общие структуры данных (Data Schemas)

### AnalysisResult JSON
Единый формат ответа, который генерирует Python ML и который Ktor отдает клиенту (внутри SSE-события `result`).

```json
{
  "sample_id": "slide_001",
  "ore_class": "ordinary | refractory | talcose",
  "talkc_pct": 14.2,
  "phases": {
    "ordinary_intergrowths": { "area_pct": 24.1, "color": "#00FF00" },
    "fine_intergrowths": { "area_pct": 61.7, "color": "#FF0000" }
  },
  "defects": [
    { "type": "crack", "area_px": 1200, "bbox": [120, 45, 180, 90] }
  ]
}
```

### AnalysisStage (Kotlin Shared Sealed Interface)
Используется на клиенте и сервере для маппинга поля `stage` из SSE.

```kotlin
package com.hackathon.shared.models

import kotlinx.serialization.Serializable

@Serializable
sealed interface AnalysisStage {
    @Serializable data class Queued(val position: Int) : AnalysisStage
    @Serializable data object Processing : AnalysisStage
    @Serializable data object Preprocessing : AnalysisStage
    @Serializable data object Segmentation : AnalysisStage
    @Serializable data object DefectDetection : AnalysisStage
    @Serializable data object Classification : AnalysisStage
    @Serializable data object Done : AnalysisStage
    @Serializable data class Error(val message: String) : AnalysisStage
}
```
