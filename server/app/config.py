"""服务端配置：全部可由环境变量或 server/.env 覆盖。

`.env` 按文件位置解析（不是当前工作目录），这样从任何目录启动 uvicorn 都能读到。
"""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

SERVER_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=SERVER_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # postgresql+psycopg://用户:密码@主机:端口/库名
    database_url: str = "postgresql+psycopg://postgres:postgres@127.0.0.1:5432/english_vocab"

    host: str = "0.0.0.0"
    port: int = 8000

    # 登录令牌的签名密钥。留空会在启动时随机生成一个 —— 只能扛到进程重启，
    # 生产/长期使用必须写进 .env。
    jwt_secret: str = ""
    jwt_expire_days: int = 30

    # 是否允许自助注册；第一个注册的账号自动成为管理员
    allow_registration: bool = True

    page_size_default: int = 200
    page_size_max: int = 5000

    # 上传 CSV 的大小上限（MB）
    max_upload_mb: int = 32

    # 同步：一次 pull 每张表最多返回多少行；一次 push 最多收多少行
    sync_page_limit: int = 1000
    sync_max_rows: int = 50000


@lru_cache
def get_settings() -> Settings:
    return Settings()
