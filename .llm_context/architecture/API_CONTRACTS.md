# API_CONTRACTS
> Version: 1.1

## Base Policy
- Все запросы от UI к Ktor Gateway должны содержать HTTP-заголовок: `X-Client-Id: <uuid>`

---

## 1. Ktor Gateway API (Внешний REST API для UI)
*Служит прослойкой между клиентом и ML. Управляет клиентами и очередью.*

### 1.1 `GET /api/queue/status`
Получение статуса очереди и лимитов для конкретного клиента.
* **Response 200 OK**:
```json
{
  "global_queue_size": 15,
  "client_active_tasks": 2,
  "python_ml_status": "ok | disconnected"
}
```

### 1.2 `POST /api/tasks`
Отправка пачки изображений в очередь на обработку.
* **Headers**: `X-Client-Id`
* **Content-Type**: `multipart/form-data`
* **Body**: `files` (Массив)
* **Response 200 OK**: Успех. Возвращает массив `task_id`.
* **Response 429 Too Many Requests**: Возвращается, если превышен лимит очереди.
* **Response 503 Service Unavailable**: Возвращается, если Python ML сервер недоступен. Ничего в очередь не добавляется.
```json
{ "error": "Python ML Server is unreachable. Please try again later." }
```

### 1.3 `DELETE /api/tasks` (отмена)
Отмена задач. Response: 200 OK.

### 1.4 `GET /api/tasks/stream` (SSE)
* **Events**: `update`, `result`, `error`, `ping`.

---

## 2. Python ML FastAPI (Внутренний ML API)
### 2.1 `GET /health`
Проверка доступности.

### 2.2 `POST /analyze`
Инференс.