import uvicorn
import logging

class EndpointFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        return bool(record.args and len(record.args) >= 3 and record.args[2] != '/health')

if __name__ == "__main__":
    # Настраиваем фильтр перед запуском uvicorn
    logging.getLogger("uvicorn.access").addFilter(EndpointFilter())
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)