# ML-KMP-NN-Hack-26-STeam

Решение команды SuperTeam для задачи автоматизации анализа руды по фотошлифам.

Проект объединяет **два взаимодополняющих контура**:

1. **R&D / Demo контур** — готовое Streamlit-приложение с каскадной ML-моделью для демонстрации качества алгоритма и визуализации результатов. - https://ore-cascade-gin79umiu9ojvq5bdhttxn.streamlit.app/
2. **Production / Enterprise контур** — масштабируемая архитектура на Kotlin Multiplatform + Ktor + Python ML service, рассчитанная на многопользовательскую эксплуатацию, очереди задач и безопасный inference.
<img width="1906" he<img width="864" height="1821" alt="diagram_nornickel_enterprise_app" src="https://github.com/user-attachments/assets/dd64adfc-c4ff-46c9-baa6-5878d265e671" />
---

## Интерфейс Enterprise Production Контура

Главный экран загрузки и очередь задач Compose UI 
<img width="1885" height="977" alt="image" src="https://github.com/user-attachments/assets/60c2593c-ace4-432c-888b-70ec05eb6710" />

Экран с результатами анализа и визуализацией ML

ight="1021" alt="image" src="https://github.com/user-attachments/assets/9b709966-771c-40ee-867b-ab70eca9613c" />


---

## Интерфейс Demo Streamlit https://ore-cascade-gin79umiu9ojvq5bdhttxn.streamlit.app/
<img width="2540" height="1320" alt="image" src="https://github.com/user-attachments/assets/35286f54-95f1-40e3-8f0b-610255fe748c" />


## Содержание

- [Что решает проект](#что-решает-проект)
- [Два контура решения](#два-контура-решения)
- [Структура репозитория](#структура-репозитория)
- [R&D контур: Streamlit demo](#rd-контур-streamlit-demo)
- [Production контур: KMP + Ktor + Python](#production-контур-kmp--ktor--python)
- [Архитектура](#архитектура)
- [Запуск через Docker Compose (Production)](#запуск-через-docker-compose-production)
- [Запуск для разработки (без Docker)](#запуск-для-разработки-без-docker)
- [Маршруты и сервисы](#маршруты-и-сервисы)
- [Что уже реализовано](#что-уже-реализовано)
- [Ограничения](#ограничения)
- [Дальнейшее развитие](#дальнейшее-развитие)

---

## Что решает проект

В геологии разметка руды по классам — **оталькованная**, **рядовая**, **труднообогатимая** — часто выполняется вручную по микроскопическим снимкам. Это:

- медленно;
- зависит от опыта и усталости эксперта;
- плохо масштабируется на большие объемы проб.

Наш проект автоматизирует этот процесс и закрывает сразу две задачи:

- **доказать работоспособность ML-подхода** на реальных изображениях;
- **показать путь промышленного внедрения** через многопользовательский интерфейс и отказоустойчивый backend.

---

## Два контура решения

### 1. R&D / Demo контур

Готовое Streamlit-приложение, в котором уже работает каскадная модель:

- Stage 1: KMeans + анализ текстуры;
- Stage 2: CNN-классификация;
- визуализация зон;
- batch-обработка;
- экспорт отчета.

Этот контур нужен для:
- быстрой демонстрации ML-результатов;
- проверки гипотез;
- понятного показа качества модели жюри и экспертам.

### 2. Production / Enterprise контур

Полноценное приложение и backend-инфраструктура:

- **Kotlin Multiplatform UI**;
- **Ktor JVM gateway**;
- **Python ML inference service**;
- очередь задач;
- backpressure;
- изоляция ML-сервиса;
- раздача статических файлов (результатов ML);
- доставка статусов клиенту через SSE.

Этот контур нужен для реальной эксплуатации в организации, когда несколько пользователей одновременно отправляют задания на inference.

---

## Структура репозитория

```text
.
├── kmp-app/                           # Kotlin Multiplatform app + Ktor backend
│   ├── app/                           # Shared UI Compose Multiplatform (Web)
│   ├── core/                          # DTOs, Error models, Network Result classes
│   └── server/                        # Ktor API Gateway & Task Queue
├── ml-service/                        # Python ML service (FastAPI)
│   ├── app/                           # ML Pipeline & Web endpoints
│   ├── outputs/                       # Сгенерированные маски и отчеты
│   └── requirements.txt
├── solutions/
│   └── ore-cascade/                   # Streamlit R&D проект
├── docker-compose.yml                 # Файл развертывания
└── README.md
```

*(Папка `.kotlin/` добавлена в `.gitignore`, чтобы не коммитить кэш зависимостей и метаданные KMP).*

---

## R&D контур: Streamlit demo

Папка: `solutions/ore-cascade/`

Основные особенности:
- веб-демо на Streamlit;
- загрузка одного изображения;
- batch-обработка;
- визуализация зон;
- экспорт результатов.

**Live demo:** `https://ore-cascade-gin79umiu9ojvq5bdhttxn.streamlit.app/`

---

## Production контур: KMP + Ktor + Python

### Состав

- **KMP UI** — клиентский Web-интерфейс на WasmJS;
- **Ktor Gateway** — бизнес-сервер, REST API и очередь задач;
- **Python ML Service** — слой inference на FastAPI.

### Зачем нужен отдельный gateway

В production нельзя пускать всех пользователей прямо в ML-процесс. Gateway решает это:
- регистрирует клиентов отдельно;
- не дает пользователям мешать друг другу;
- управляет очередью задач;
- ограничивает нагрузку;
- хранит статусы задач;
- доставляет обновления по SSE;
- защищает ML worker от перегрузки.

### Зачем нужен отдельный ML service

ML-инференс ресурсоемкий. Поэтому он вынесен отдельно:
- выполняется изолированно;
- обрабатывает задачи последовательно;
- генерирует визуализации и раздает их как статику (`/outputs`);
- не "роняет" JVM-бэкенд;
- может масштабироваться независимо.

---

## Архитектура

### Production-сценарий

1. Геолог загружает изображение через web UI.
2. KMP-клиент отправляет задачу в Ktor gateway (POST `/api/tasks`).
3. Gateway принимает Multipart-файл, генерирует `taskId` и ставит задачу в очередь.
4. Python ML service вызывается сервером для инференса, получая само изображение и `sample_id`.
5. ML-модель классифицирует руду, сохраняет графики в `outputs/` и возвращает JSON.
6. Клиент получает статусы `Queued -> Processing -> Done` через Server-Sent Events (SSE).
7. UI загружает графики напрямую из FastAPI (`http://localhost:8000/outputs/...`) и отображает финальный вердикт.

---

## Запуск через Docker Compose (Production)

Для развертывания всего production-контура одной командой:

```bash
docker compose up --build -d
```

**Что произойдет:**
1. Поднимется `ml-service` на порту `8000`.
2. Поднимется `ktor-gateway` на порту `8080`. Контейнер использует Gradle-кэш для быстрого рестарта.
3. Ktor-сервер будет "знать" о ML-сервисе через переменную окружения `ML_URL`.

**Доступность:**
- Backend API Gateway: `http://localhost:8080`
- ML Service API: `http://localhost:8000`

---

## Запуск для разработки (без Docker)

### 1. Python ML service

```bash
cd ml-service
python -m venv .venv
# Windows: .venv\Scripts\activate
# Mac/Linux: source .venv/bin/activate
pip install -r requirements.txt
python run.py
```
*Сервис поднимется на `http://localhost:8000`*

### 2. Ktor + KMP app (Terminal 1)

```bash
cd kmp-app
./gradlew :server:run
```
*Ktor gateway поднимется на `http://localhost:8080`*

### 3. Ktor + KMP app (Terminal 2 - Web Client)

```bash
cd kmp-app
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
```
*Откроется браузер с Web UI на базе Compose Multiplatform.*

---

## Маршруты и сервисы

### Python ML service (`:8000`)
- `GET /health` — проверка доступности.
- `POST /analyze` — запуск ML-пайплайна. Принимает `file` и `sample_id` (Form).
- `GET /outputs/{filename}` — раздача сгенерированных изображений и масок (StaticFiles).

### Ktor gateway (`:8080`)
- `GET /health`
- `POST /api/tasks` — загрузка массива изображений.
- `GET /api/tasks/stream` — SSE endpoint для получения live-обновлений.
- `DELETE /api/tasks` — отмена задач.

---

## Что уже реализовано

### В R&D контуре
- Рабочая каскадная модель.
- Streamlit UI для проверки гипотез.

### В production контуре
- Загрузка нескольких изображений (Multipart).
- Очередь задач с отслеживанием прогресса.
- Статусы по SSE.
- Динамическая подгрузка сгенерированных ML масок и карт плотности в UI Compose.
- Разделение клиентов.
- Интеграция Ktor и Python (FastAPI).
- UI Result Screen с подробной аналитикой и русифицированными лейблами.

---

## Ограничения

- Фронтенд на WasmJS строго привязан к локальным адресам `localhost:8000` и `localhost:8080` (настраивается через константы перед деплоем в облако).
- Stage 1 в ML-пайплайне работает на CPU.
- Для новых типов изображений может потребоваться повторная калибровка ML-модели.

---

## Дальнейшее развитие

- Экспертная валидация и ручная корректировка результата.
- Сбор датасета для дообучения.
- Сохранение разметки в базу данных (PostgreSQL / SQLite).
- Горизонтальное масштабирование ML worker-ов (Celery / RabbitMQ).

---

## Идея проекта в одном абзаце

Мы сделали не только ML-модель, но и показали, как превратить её в реальный промышленный инструмент: от исследовательского демо на Streamlit до масштабируемой production-архитектуры с очередями, генерацией статики, безопасным inference и многопользовательским интерфейсом на Kotlin Multiplatform.
