"""密码哈希、JWT 与「当前用户」依赖。

- 密码用 Argon2（`pwdlib`，FastAPI 文档现在推荐的库，取代已停止维护的 passlib）。
- 令牌用 JWT（HS256），载荷只放 `sub`（用户 id）、`role`、`exp`，不放任何隐私数据。
- 权限模型只有两级：`admin` 能改公共词库、能往任意词表导入；`user` 只能动自己的词表
  和自己的进度。所有 SQL 都带 `user_id` 条件，越权在数据层就被挡住，而不是靠 if 判断。
"""

from __future__ import annotations

import secrets
import uuid
from datetime import datetime, timedelta, timezone

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pwdlib import PasswordHash
from sqlalchemy.ext.asyncio import AsyncSession

from .config import get_settings
from .db import get_session
from .models import User

settings = get_settings()

_password = PasswordHash.recommended()

# auto_error=False：没带令牌时返回 None，由 get_current_user 决定要不要报 401。
# 用 HTTPBearer 而不是手撕 Header，Swagger 的 /docs 才会出现 Authorize 按钮。
_bearer_scheme = HTTPBearer(auto_error=False, description="登录后拿到的 accessToken")

# 没配 JWT_SECRET 就临时生成一个：单机调试够用，重启后旧令牌全部失效
_JWT_SECRET = settings.jwt_secret or secrets.token_urlsafe(48)
ALGORITHM = "HS256"


def hash_password(raw: str) -> str:
    return _password.hash(raw)


def verify_password(raw: str, hashed: str) -> bool:
    try:
        return _password.verify(raw, hashed)
    except Exception:  # noqa: BLE001 - 哈希格式坏了也只算验证失败
        return False


def create_access_token(user: User) -> tuple[str, datetime]:
    expires_at = datetime.now(timezone.utc) + timedelta(days=settings.jwt_expire_days)
    payload = {
        "sub": str(user.id),
        "role": user.role,
        "usr": user.username,
        "exp": expires_at,
        "iat": datetime.now(timezone.utc),
    }
    return jwt.encode(payload, _JWT_SECRET, algorithm=ALGORITHM), expires_at


def _decode(token: str) -> dict:
    try:
        return jwt.decode(token, _JWT_SECRET, algorithms=[ALGORITHM])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "登录已过期，请重新登录") from None
    except jwt.PyJWTError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "令牌无效") from None


async def get_optional_user(
    session: AsyncSession = Depends(get_session),
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> User | None:
    if credentials is None or not credentials.credentials.strip():
        return None
    token = credentials.credentials.strip()
    payload = _decode(token)
    try:
        user_id = uuid.UUID(str(payload.get("sub")))
    except (ValueError, TypeError):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "令牌无效") from None
    user = await session.get(User, user_id)
    if user is None or user.disabled:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "账号不存在或已停用")
    return user


async def get_current_user(user: User | None = Depends(get_optional_user)) -> User:
    if user is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            "需要登录",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user


async def require_admin(user: User = Depends(get_current_user)) -> User:
    if not user.is_admin:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "需要管理员权限")
    return user


CurrentUser = Depends(get_current_user)
AdminUser = Depends(require_admin)
