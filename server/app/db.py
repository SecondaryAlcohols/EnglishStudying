"""数据库连接（异步）。

驱动是 psycopg 3 的 async 实现（URL 前缀 `postgresql+psycopg`，SQLAlchemy 会自动
挑 async 版本）。连接池的 pre_ping 会在借出前探活，PostgreSQL 重启后不用重启服务。
"""

from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from .config import get_settings

_settings = get_settings()

engine = create_async_engine(
    _settings.database_url,
    pool_pre_ping=True,
    pool_size=5,
    max_overflow=10,
    echo=False,
)

SessionLocal = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI 依赖：一次请求一个会话。"""
    async with SessionLocal() as session:
        yield session
