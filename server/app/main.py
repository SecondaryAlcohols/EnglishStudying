"""HTTP 接口。

权限模型：

* `/api/health`、`/api/auth/register`、`/api/auth/login` 公开。
* 其余接口都要 `Authorization: Bearer <token>`。
* 词库 `words` **没有写接口**：普通用户改不到它。`POST /api/decks/{id}/import` 只有
  管理员能调（这是唯一会写 words 的入口）。
* 普通用户能改的只有**自己名下**的 `decks` 行和这些词表的 `deck_words` 挂载。
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import secrets
import socket
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Annotated, Literal
from urllib.parse import quote

from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, Response
from sqlalchemy import String, case, func, literal, or_, select, text, union_all
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from . import sync as sync_service
from .config import get_settings
from .db import SessionLocal, engine, get_session
from .importer import (
    COLUMN_ORDER,
    EXPORT_HEADERS,
    export_rows,
    import_rows,
    import_user_words,
    parse_upload,
)
from .models import Deck, User, UserWord, Word, deck_user_words, deck_words
from .schemas import (
    AdminUserCreate,
    DeckCreate,
    DeckImportFrom,
    DeckImportResult,
    DeckOut,
    DeckUpdate,
    DeckWordsIn,
    DeckWordsResult,
    HealthOut,
    ImportResult,
    LoginIn,
    MyWordIn,
    MyWordUpdate,
    PasswordChange,
    RegisterIn,
    SyncPull,
    SyncPush,
    SyncPushResult,
    SyncStatus,
    TokenOut,
    UserOut,
    WordOut,
    WordPage,
    WordSearchHit,
    WordSearchPage,
)
from .security import (
    create_access_token,
    get_current_user,
    get_optional_user,
    hash_password,
    require_admin,
    verify_password,
)

VERSION = "1.2.0"
settings = get_settings()

# 校验错误统一翻成一句中文：前端只要把 detail 当字符串显示就行，
# 不用再猜 FastAPI 那个 [{loc, msg, type}] 的数组结构。
FIELD_LABELS = {
    "username": "用户名",
    "password": "密码",
    "old_password": "原密码",
    "new_password": "新密码",
    "display_name": "昵称",
    "name": "词表名",
    "description": "描述",
    "headwords": "单词列表",
    "device_id": "设备 ID",
    "device_name": "设备名",
    "platform": "平台",
    "file": "文件",
}


def _describe_validation(errors: list[dict]) -> str:
    messages: list[str] = []
    for error in errors:
        loc = [str(part) for part in error.get("loc", []) if part not in ("body", "query", "path")]
        field = FIELD_LABELS.get(loc[-1], loc[-1] if loc else "")
        kind = error.get("type", "")
        ctx = error.get("ctx") or {}
        if kind == "string_too_short":
            text = f"至少 {ctx.get('min_length')} 个字符"
        elif kind == "string_too_long":
            text = f"最多 {ctx.get('max_length')} 个字符"
        elif kind == "too_short":
            text = f"至少要有 {ctx.get('min_length')} 项"
        elif kind == "missing":
            text = "必填"
        elif kind in ("string_type", "int_type", "bool_type", "float_type"):
            text = "类型不对"
        elif kind == "json_invalid":
            text = "不是合法的 JSON"
        else:
            text = error.get("msg", "格式不正确")
        messages.append(f"{field}：{text}" if field else text)
    return "；".join(messages) or "请求参数不正确"


SessionDep = Annotated[AsyncSession, Depends(get_session)]
CurrentUser = Annotated[User, Depends(get_current_user)]
OptionalUser = Annotated[User | None, Depends(get_optional_user)]


@asynccontextmanager
async def lifespan(_: FastAPI):
    # 表结构交给 Alembic（server/migrations）。这里只检查一下有没有跑过迁移，
    # 免得服务能起来但每个接口都报 relation does not exist。
    try:
        async with SessionLocal() as session:
            await session.execute(text("SELECT 1 FROM decks LIMIT 1"))
    except Exception:  # noqa: BLE001 - 只是启动时的提醒
        print("⚠ 数据库表还没建，先跑：uv run alembic upgrade head")
    yield
    await engine.dispose()


app = FastAPI(
    title="词表服务",
    version=VERSION,
    description="词表分发 + 学习进度同步。词库只读，用户只能改自己名下的词表。",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST", "PATCH", "DELETE"],
    allow_headers=["*"],
)


@app.exception_handler(RequestValidationError)
async def on_validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=422, content={"detail": _describe_validation(exc.errors())})


# ---------------------------------------------------------------- 账号


def _user_out(user: User) -> UserOut:
    return UserOut(
        id=user.id,
        username=user.username,
        display_name=user.display_name,
        role=user.role,
        created_at=user.created_at,
        last_login_at=user.last_login_at,
    )


def _normalize_username(raw: str) -> str:
    return raw.strip().lower()


@app.post("/api/auth/register", response_model=TokenOut, status_code=201, tags=["auth"])
async def register(body: RegisterIn, session: SessionDep) -> TokenOut:
    """注册。**第一个注册的账号自动是管理员**，之后按 `ALLOW_REGISTRATION` 开关决定是否开放。"""
    username = _normalize_username(body.username)
    if len(username) < 3:
        raise HTTPException(400, "用户名至少 3 个字符")

    existing_users = await session.scalar(select(func.count()).select_from(User)) or 0
    if existing_users > 0 and not settings.allow_registration:
        raise HTTPException(403, "已关闭自助注册，请联系管理员")

    taken = await session.scalar(select(User.id).where(User.username == username))
    if taken is not None:
        raise HTTPException(409, f"用户名「{username}」已被占用")

    user = User(
        username=username,
        password_hash=hash_password(body.password),
        display_name=(body.display_name or "").strip() or None,
        role="admin" if existing_users == 0 else "user",
    )
    session.add(user)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise HTTPException(409, "用户名已被占用") from None
    await session.refresh(user)

    token, expires_at = create_access_token(user)
    return TokenOut(access_token=token, expires_at=expires_at, user=_user_out(user))


@app.post("/api/auth/login", response_model=TokenOut, tags=["auth"])
async def login(body: LoginIn, session: SessionDep) -> TokenOut:
    username = _normalize_username(body.username)
    user = await session.scalar(select(User).where(User.username == username))
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(401, "用户名或密码错误")
    if user.disabled:
        raise HTTPException(403, "账号已停用")

    user.last_login_at = datetime.now(timezone.utc)
    await session.commit()

    token, expires_at = create_access_token(user)
    return TokenOut(access_token=token, expires_at=expires_at, user=_user_out(user))


@app.get("/api/auth/me", response_model=UserOut, tags=["auth"])
async def me(user: CurrentUser) -> UserOut:
    return _user_out(user)


@app.post("/api/auth/password", status_code=204, tags=["auth"])
async def change_password(
    body: PasswordChange, user: CurrentUser, session: SessionDep
) -> Response:
    if not verify_password(body.old_password, user.password_hash):
        raise HTTPException(400, "原密码不正确")
    user.password_hash = hash_password(body.new_password)
    await session.commit()
    return Response(status_code=204)


# ---------------------------------------------------------------- 账号管理（管理员）


@app.get("/api/users", response_model=list[UserOut], tags=["admin"])
async def list_users(
    _: User = Depends(require_admin), session: AsyncSession = Depends(get_session)
) -> list[UserOut]:
    users = list(await session.scalars(select(User).order_by(User.created_at)))
    return [_user_out(user) for user in users]


@app.post("/api/users", response_model=UserOut, status_code=201, tags=["admin"])
async def admin_create_user(
    body: AdminUserCreate,
    _: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> UserOut:
    """管理员建号。和自助注册的区别是可以直接指定角色。"""
    username = _normalize_username(body.username)
    if await session.scalar(select(User.id).where(User.username == username)) is not None:
        raise HTTPException(409, f"用户名「{username}」已被占用")
    user = User(
        username=username,
        password_hash=hash_password(body.password),
        display_name=(body.display_name or "").strip() or None,
        role=body.role,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return _user_out(user)


@app.delete("/api/users/{user_id}", status_code=204, tags=["admin"])
async def admin_delete_user(
    user_id: uuid.UUID,
    admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> Response:
    """删号。会连带删除他名下的词表、私有词条和同步数据（外键 CASCADE）。"""
    if user_id == admin.id:
        raise HTTPException(400, "不能删除自己")
    user = await session.get(User, user_id)
    if user is None:
        raise HTTPException(404, "账号不存在")
    await session.delete(user)
    await session.commit()
    return Response(status_code=204)


# ---------------------------------------------------------------- 权限判定


# 分享码字母表：Crockford Base32，去掉 I / L / O / U，避免 1-l、0-O 认错
_SHARE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
SHARE_CODE_LEN = 8


def _normalize_share_code(raw: str) -> str:
    """统一成大写、只留字母数字：`k7q2-9f3m` 和 `K7Q2 9F3M` 都变成 `K7Q29F3M`。

    去掉分隔符是为了「存一份、怎么输入都能查到」，用户看到的横线只是排版。
    """
    return "".join(ch for ch in raw.upper() if ch.isalnum())


def _new_share_code() -> str:
    return "".join(secrets.choice(_SHARE_ALPHABET) for _ in range(SHARE_CODE_LEN))


async def _ensure_share_code_free(
    session: AsyncSession, code: str, *, exclude_id: int | None = None
) -> None:
    stmt = select(Deck.id).where(Deck.share_code == code)
    if exclude_id is not None:
        stmt = stmt.where(Deck.id != exclude_id)
    if await session.scalar(stmt) is not None:
        raise HTTPException(409, f"分享码「{code}」已经被用了，换一个")


async def _generate_share_code(session: AsyncSession, deck: Deck) -> str:
    """生成一个没被占用的随机码。池子 32^8 ≈ 1.1 万亿，重试上限只是兜底。"""
    for _ in range(12):
        code = _new_share_code()
        if await session.scalar(select(Deck.id).where(Deck.share_code == code)) is None:
            return code
    raise HTTPException(503, "生成分享码失败，请稍后重试")


async def _load_deck(
    session: AsyncSession, deck_id: int, user: User, *, write: bool
) -> Deck:
    """取词表并校验权限。

    * 官方词表（`owner_id IS NULL`）：所有人可读，只有管理员可写。
    * 公开词表（`visibility = public`）：**登录用户**都能读、下载、挂到自己表上，但只有作者能改。
    * 私有词表：只有主人和管理员能读；别人拿到 id 只会得到 404，不泄漏存在性。

    公开表在「写」场景下回 403 而不是 404 —— 它本来就是公开的，没有隐藏的必要。
    """
    deck = await session.get(Deck, deck_id)
    if deck is None:
        raise HTTPException(404, f"词表 {deck_id} 不存在")

    if deck.owner_id is None:
        if write and not user.is_admin:
            raise HTTPException(403, "官方词表只有管理员能修改")
        return deck

    if user.id == deck.owner_id or user.is_admin:
        return deck

    if deck.visibility == "public":
        if write:
            raise HTTPException(403, "公开词表只有作者能修改，你可以「复制一份」到自己的词表")
        return deck

    raise HTTPException(404, f"词表 {deck_id} 不存在")


def _can_write(deck: Deck, user: User) -> bool:
    return user.is_admin or (deck.owner_id is not None and deck.owner_id == user.id)


# ---------------------------------------------------------------- 查询小工具


# 公共词和私有词要拼成同一张“词表内容”，列的别名必须完全一致
def _deck_entries(deck_id: int):
    """词表里的全部条目：公共词库的词 + 词表主人的私有词。

    两边 id 各自自增，可能撞号，所以每一行都带 `source`（global / private）区分。
    """
    global_part = (
        select(
            Word.id.label("id"),
            literal("global", String).label("source"),
            Word.headword.label("headword"),
            Word.phonetic.label("phonetic"),
            Word.part_of_speech.label("part_of_speech"),
            Word.meanings.label("meanings"),
            Word.mnemonic.label("mnemonic"),
            Word.roots.label("roots"),
            Word.derivations.label("derivations"),
            Word.examples.label("examples"),
            Word.synonyms.label("synonyms"),
            Word.tags.label("tags"),
            Word.frequency.label("frequency"),
        )
        .join(deck_words, deck_words.c.word_id == Word.id)
        .where(deck_words.c.deck_id == deck_id)
    )
    private_part = (
        select(
            UserWord.id.label("id"),
            literal("private", String).label("source"),
            UserWord.headword.label("headword"),
            UserWord.phonetic.label("phonetic"),
            UserWord.part_of_speech.label("part_of_speech"),
            UserWord.meanings.label("meanings"),
            UserWord.mnemonic.label("mnemonic"),
            UserWord.roots.label("roots"),
            UserWord.derivations.label("derivations"),
            UserWord.examples.label("examples"),
            UserWord.synonyms.label("synonyms"),
            UserWord.tags.label("tags"),
            UserWord.frequency.label("frequency"),
        )
        .join(deck_user_words, deck_user_words.c.user_word_id == UserWord.id)
        .where(deck_user_words.c.deck_id == deck_id)
    )
    return union_all(global_part, private_part).subquery("entries")


def _entry_order(entries):
    """COCA 排名升序，0（查不到）垫底，同频按字母。"""
    return (
        case((entries.c.frequency <= 0, 1), else_=0),
        entries.c.frequency,
        func.lower(entries.c.headword),
    )


async def _word_count(session: AsyncSession, deck_id: int) -> int:
    global_count = await session.scalar(
        select(func.count()).select_from(deck_words).where(deck_words.c.deck_id == deck_id)
    )
    private_count = await session.scalar(
        select(func.count()).select_from(deck_user_words).where(deck_user_words.c.deck_id == deck_id)
    )
    return (global_count or 0) + (private_count or 0)

async def _mount_links_from(
    session: AsyncSession, target: Deck, source: Deck, user: User, *, mode: str
) -> tuple[int, int, int]:
    """把 `source` 的词**挂**到 `target` 上：只建挂载行，不复制词条本身。

    * 公共词库的词：直接挂 `deck_words`。
    * 私有词：只有源表是**你自己的**才会一起挂 —— 别人表里的私有词是他自己的数据，
      复制过来既越权也没意义（他有他的改法，你有你的）。跳过的数量计入返回值。

    返回 `(added, removed, skipped_private)`。
    """
    global_ids = [
        row[0]
        for row in await session.execute(
            select(deck_words.c.word_id).where(deck_words.c.deck_id == source.id)
        )
    ]
    private_ids: list[int] = []
    skipped = 0
    if source.owner_id is not None and source.owner_id == user.id:
        private_ids = [
            row[0]
            for row in await session.execute(
                select(deck_user_words.c.user_word_id).where(
                    deck_user_words.c.deck_id == source.id
                )
            )
        ]
    else:
        skipped = (
            await session.scalar(
                select(func.count())
                .select_from(deck_user_words)
                .where(deck_user_words.c.deck_id == source.id)
            )
            or 0
        )

    removed = 0
    if mode == "replace":
        dropped_global = await session.execute(
            deck_words.delete().where(deck_words.c.deck_id == target.id)
        )
        dropped_private = await session.execute(
            deck_user_words.delete().where(deck_user_words.c.deck_id == target.id)
        )
        removed = (dropped_global.rowcount or 0) + (dropped_private.rowcount or 0)

    have_global = {
        row[0]
        for row in await session.execute(
            select(deck_words.c.word_id).where(deck_words.c.deck_id == target.id)
        )
    }
    have_private = {
        row[0]
        for row in await session.execute(
            select(deck_user_words.c.user_word_id).where(deck_user_words.c.deck_id == target.id)
        )
    }

    added = 0
    for word_id in global_ids:
        if word_id in have_global:
            continue
        await session.execute(
            deck_words.insert().values(deck_id=target.id, word_id=word_id)
        )
        added += 1
    for word_id in private_ids:
        if word_id in have_private:
            continue
        await session.execute(
            deck_user_words.insert().values(deck_id=target.id, user_word_id=word_id)
        )
        added += 1
    return added, removed, skipped


def _to_deck_out(deck: Deck, count: int, user: User, owner_name: str | None = None) -> DeckOut:
    return DeckOut(
        id=deck.id,
        name=deck.name,
        description=deck.description,
        word_count=count,
        created_at=deck.created_at,
        updated_at=deck.updated_at,
        csv_url=f"/api/decks/{deck.id}/export?format=csv",
        json_url=f"/api/decks/{deck.id}/export?format=json",
        owner_id=deck.owner_id,
        owner_name=owner_name,
        official=deck.owner_id is None,
        mine=deck.owner_id is not None and deck.owner_id == user.id,
        # 官方表对所有人可见，对外就报 public；自己的表按列里的值
        visibility="public" if deck.owner_id is None else deck.visibility,
        share_code=deck.share_code,
        editable=_can_write(deck, user),
    )


def _entry_to_out(row) -> WordOut:
    """`_deck_entries` 的一行 → 响应模型。"""
    return WordOut(
        id=row.id,
        source=row.source,
        headword=row.headword,
        phonetic=row.phonetic,
        pos=row.part_of_speech,
        meaning=row.meanings,
        mnemonic=row.mnemonic,
        root=row.roots,
        derivation=row.derivations,
        example=row.examples,
        synonym=row.synonyms,
        tags=row.tags,
        frequency=row.frequency,
    )


def _to_word_out(word: Word) -> WordOut:
    return WordOut(
        headword=word.headword,
        phonetic=word.phonetic,
        pos=word.part_of_speech,
        meaning=word.meanings,
        mnemonic=word.mnemonic,
        root=word.roots,
        derivation=word.derivations,
        example=word.examples,
        synonym=word.synonyms,
        tags=word.tags,
        frequency=word.frequency,
    )


def _etag(deck: Deck, count: int) -> str:
    updated = deck.updated_at
    if updated is None:
        stamp = "0"
    else:
        stamp = (updated if updated.tzinfo else updated.replace(tzinfo=timezone.utc)).astimezone(
            timezone.utc
        ).isoformat()
    raw = f"{deck.id}:{stamp}:{count}".encode()
    return 'W/"' + hashlib.sha1(raw).hexdigest()[:16] + '"'


# ---------------------------------------------------------------- 只读接口


@app.get("/api/health", response_model=HealthOut, tags=["meta"])
async def health(session: SessionDep) -> HealthOut:
    try:
        await session.execute(text("SELECT 1"))
        database = "ok"
    except Exception:  # noqa: BLE001 - 健康检查只关心通不通
        database = "error"
    return HealthOut(
        status="ok" if database == "ok" else "degraded",
        version=VERSION,
        database=database,
        deck_count=await session.scalar(select(func.count()).select_from(Deck)) or 0,
        word_count=await session.scalar(select(func.count()).select_from(Word)) or 0,
    )


@app.get("/api/decks", response_model=list[DeckOut], tags=["decks"])
async def list_decks(
    user: CurrentUser,
    session: SessionDep,
    scope: str = Query(
        "all",
        pattern="^(all|official|public|mine)$",
        description="official=官方，public=别人公开的，mine=我自己的，all=前三者之和",
    ),
    q: str | None = Query(None, description="按词表名 / 简介 / 作者搜"),
    all: bool = Query(False, alias="all", description="管理员可用：看所有人的词表"),
) -> list[DeckOut]:
    """一次列全，客户端不用为四个页签各发一次请求。"""
    global_count = (
        select(func.count())
        .select_from(deck_words)
        .where(deck_words.c.deck_id == Deck.id)
        .scalar_subquery()
    )
    private_count = (
        select(func.count())
        .select_from(deck_user_words)
        .where(deck_user_words.c.deck_id == Deck.id)
        .scalar_subquery()
    )
    stmt = (
        select(Deck, (global_count + private_count).label("word_count"), User.username)
        .outerjoin(User, User.id == Deck.owner_id)
        .group_by(Deck.id, User.username)
        .order_by(Deck.owner_id.isnot(None), func.lower(Deck.name))
    )

    if not (user.is_admin and all):
        if scope == "official":
            stmt = stmt.where(Deck.owner_id.is_(None))
        elif scope == "mine":
            stmt = stmt.where(Deck.owner_id == user.id)
        elif scope == "public":
            # 「公开」页签 = 别人公开给我的，不含自己那张（自己的在「我的」里，可编辑）
            stmt = stmt.where(
                Deck.owner_id.isnot(None),
                Deck.owner_id != user.id,
                Deck.visibility == "public",
            )
        else:
            # 官方 + 公开 + 自己的（自己的私有表也在里面）
            stmt = stmt.where(
                or_(
                    Deck.owner_id.is_(None),
                    Deck.owner_id == user.id,
                    Deck.visibility == "public",
                )
            )

    if q and q.strip():
        pattern = f"%{q.strip()}%"
        stmt = stmt.where(
            or_(
                Deck.name.ilike(pattern),
                func.coalesce(Deck.description, "").ilike(pattern),
                func.coalesce(User.username, "").ilike(pattern),
            )
        )

    rows = (await session.execute(stmt)).all()
    return [_to_deck_out(deck, count, user, owner) for deck, count, owner in rows]


@app.get("/api/decks/by-code/{code}", response_model=DeckOut, tags=["decks"])
async def get_deck_by_code(code: str, user: CurrentUser, session: SessionDep) -> DeckOut:
    """按分享码找词表。必须登录；别人改回私有之后就查不到了。"""
    normalized = _normalize_share_code(code)
    if len(normalized) < 4:
        raise HTTPException(404, "分享码不对")
    deck = await session.scalar(select(Deck).where(Deck.share_code == normalized))
    if deck is None:
        raise HTTPException(404, "分享码不对，或者对方已经关闭了分享")
    if (
        deck.owner_id is not None
        and deck.owner_id != user.id
        and not user.is_admin
        and deck.visibility != "public"
    ):
        # 码还在，但表已经改回私有 —— 对别人等同于不存在
        raise HTTPException(404, "分享码不对，或者对方已经关闭了分享")
    owner_name = None
    if deck.owner_id is not None:
        owner_name = await session.scalar(select(User.username).where(User.id == deck.owner_id))
    return _to_deck_out(deck, await _word_count(session, deck.id), user, owner_name)


@app.get("/api/decks/{deck_id}", response_model=DeckOut, tags=["decks"])
async def get_deck(deck_id: int, user: CurrentUser, session: SessionDep) -> DeckOut:
    deck = await _load_deck(session, deck_id, user, write=False)
    owner_name = None
    if deck.owner_id is not None:
        owner_name = await session.scalar(select(User.username).where(User.id == deck.owner_id))
    return _to_deck_out(deck, await _word_count(session, deck_id), user, owner_name)


@app.get("/api/decks/{deck_id}/words", response_model=WordPage, tags=["decks"])
async def list_words(
    deck_id: int,
    user: CurrentUser,
    session: SessionDep,
    offset: int = Query(0, ge=0),
    limit: int | None = Query(None, ge=1, le=settings.page_size_max),
    q: str | None = Query(None, description="按词头或释义模糊搜索"),
) -> WordPage:
    deck = await _load_deck(session, deck_id, user, write=False)
    size = limit or settings.page_size_default

    entries = _deck_entries(deck_id)
    stmt = select(entries).order_by(*_entry_order(entries))
    if q:
        pattern = f"%{q.strip()}%"
        stmt = stmt.where(
            entries.c.headword.ilike(pattern)
            | func.coalesce(entries.c.meanings, "").ilike(pattern)
        )

    total = await session.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    rows = (await session.execute(stmt.offset(offset).limit(size))).all()
    return WordPage(
        deck_id=deck.id,
        name=deck.name,
        total=total,
        offset=offset,
        limit=size,
        items=[_entry_to_out(row) for row in rows],
    )


@app.get("/api/decks/{deck_id}/export", tags=["decks"])
async def export_deck(
    deck_id: int,
    user: CurrentUser,
    session: SessionDep,
    format: Literal["csv", "json"] = Query("csv", description="下载格式"),
    pretty: bool = Query(False, description="JSON 是否缩进"),
    if_none_match: Annotated[str | None, Header()] = None,
):
    """下载整张词表。文件可以直接在 App 里「导入」——格式就是 App 的导入格式。"""
    deck = await _load_deck(session, deck_id, user, write=False)
    entries = _deck_entries(deck_id)
    words = (await session.execute(
        select(entries).order_by(*_entry_order(entries))
    )).all()
    tag = _etag(deck, len(words))

    headers = {"ETag": tag, "Cache-Control": "no-cache", "X-Deck-Name": quote(deck.name)}
    if if_none_match and if_none_match == tag:
        return Response(status_code=304, headers=headers)

    if format == "json":
        _, rows = export_rows(words)
        payload = []
        for line in rows:
            item = {EXPORT_HEADERS[column]: line[index] for index, column in enumerate(COLUMN_ORDER)}
            item["deck"] = deck.name
            payload.append(item)
        body = json.dumps(payload, ensure_ascii=False, indent=2 if pretty else None)
        content = body.encode("utf-8")
        media_type = "application/json; charset=utf-8"
        extension = "json"
    else:
        head, rows = export_rows(words)
        buffer = io.StringIO(newline="")
        writer = csv.writer(buffer, lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
        writer.writerow(head)
        writer.writerows(rows)
        # BOM 是为了让 Excel 直接双击也不乱码；App 的解析器会自己剥掉
        content = buffer.getvalue().encode("utf-8-sig")
        media_type = "text/csv; charset=utf-8"
        extension = "csv"

    filename = f"{deck.name}.{extension}"
    headers["Content-Disposition"] = (
        f'attachment; filename="deck-{deck.id}.{extension}"; '
        f"filename*=UTF-8''{quote(filename)}"
    )
    return Response(content=content, media_type=media_type, headers=headers)


# ---------------------------------------------------------------- 词表写接口


@app.post("/api/decks", response_model=DeckOut, status_code=201, tags=["decks"])
async def create_deck(body: DeckCreate, user: CurrentUser, session: SessionDep) -> DeckOut:
    """新建词表。

    * `visibility=public` 时会顺手生成一个分享码。
    * `seedFromDeckId` 只**挂载**源表的词，不复制词条：源表更新，这边跟着变；
      源表删词，这边也会少（同一份数据）。想彻底独立就先复制一份再说。
    """
    source: Deck | None = None
    if body.seed_from_deck_id is not None:
        source = await _load_deck(session, body.seed_from_deck_id, user, write=False)

    deck = Deck(
        owner_id=user.id,
        name=body.name.strip(),
        description=(body.description or "").strip() or None,
        visibility=body.visibility,
    )
    if body.visibility == "public":
        deck.share_code = await _generate_share_code(session, deck)
    session.add(deck)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise HTTPException(409, f"你已经有一张叫「{body.name}」的词表了") from None
    await session.refresh(deck)

    count = 0
    if source is not None and source.id != deck.id:
        added, _, _ = await _mount_links_from(session, deck, source, user, mode="append")
        if added:
            deck.updated_at = datetime.now(timezone.utc)
            await session.commit()
        count = added
    return _to_deck_out(deck, count, user, user.username)


@app.patch("/api/decks/{deck_id}", response_model=DeckOut, tags=["decks"])
async def update_deck(
    deck_id: int, body: DeckUpdate, user: CurrentUser, session: SessionDep
) -> DeckOut:
    """改名、简介、可见性、分享码。官方词表只有管理员能调。"""
    deck = await _load_deck(session, deck_id, user, write=True)

    if body.name is not None:
        deck.name = body.name.strip()
    if body.description is not None:
        deck.description = body.description.strip() or None
    if body.visibility is not None:
        deck.visibility = body.visibility
        # 第一次公开时给个码，免得用户还要多点一次「生成」
        if body.visibility == "public" and not deck.share_code:
            deck.share_code = await _generate_share_code(session, deck)
    if body.share_code is not None:
        code = _normalize_share_code(body.share_code)
        if len(code) < 4:
            raise HTTPException(400, "分享码至少 4 位字母或数字")
        await _ensure_share_code_free(session, code, exclude_id=deck.id)
        deck.share_code = code

    deck.updated_at = datetime.now(timezone.utc)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise HTTPException(409, "同名词表或分享码已存在") from None
    owner_name = None
    if deck.owner_id is not None:
        owner_name = await session.scalar(select(User.username).where(User.id == deck.owner_id))
    return _to_deck_out(deck, await _word_count(session, deck_id), user, owner_name)


@app.post("/api/decks/{deck_id}/share-code/reset", response_model=DeckOut, tags=["decks"])
async def reset_share_code(
    deck_id: int, user: CurrentUser, session: SessionDep
) -> DeckOut:
    """换一个随机分享码，旧的立刻失效。"""
    deck = await _load_deck(session, deck_id, user, write=True)
    deck.share_code = await _generate_share_code(session, deck)
    deck.updated_at = datetime.now(timezone.utc)
    await session.commit()
    owner_name = None
    if deck.owner_id is not None:
        owner_name = await session.scalar(select(User.username).where(User.id == deck.owner_id))
    return _to_deck_out(deck, await _word_count(session, deck_id), user, owner_name)


@app.post("/api/decks/{deck_id}/import-from", response_model=DeckImportResult, tags=["decks"])
async def import_from_deck(
    deck_id: int, body: DeckImportFrom, user: CurrentUser, session: SessionDep
) -> DeckImportResult:
    """把另一张词表的词挂到这张表上（不复制词条）。

    `mode=replace` 先清空本表已有挂载；`append` 只补差集。
    别人的私有词不会被挂过来，数量在 `skippedPrivate` 里。
    """
    if body.source_id == deck_id:
        raise HTTPException(400, "不能从自己导入自己")
    target = await _load_deck(session, deck_id, user, write=True)
    source = await _load_deck(session, body.source_id, user, write=False)

    added, removed, skipped = await _mount_links_from(
        session, target, source, user, mode=body.mode
    )
    if added or removed:
        target.updated_at = datetime.now(timezone.utc)
    await session.commit()
    return DeckImportResult(
        deck_id=target.id,
        source_id=source.id,
        added=added,
        removed=removed,
        skipped_private=skipped,
        total=await _word_count(session, target.id),
    )


@app.delete("/api/decks/{deck_id}", status_code=204, tags=["decks"])
async def delete_deck(deck_id: int, user: CurrentUser, session: SessionDep) -> Response:
    deck = await _load_deck(session, deck_id, user, write=True)
    await session.delete(deck)
    await session.commit()
    return Response(status_code=204)


@app.post("/api/decks/{deck_id}/words", response_model=DeckWordsResult, tags=["decks"])
async def add_words(
    deck_id: int, body: DeckWordsIn, user: CurrentUser, session: SessionDep
) -> DeckWordsResult:
    """把词挂到自己的词表上。

    查找顺序：**先找自己的私有词条，再找公共词库**（同名时以自己改过的版本为准）。
    两边都没有的，列在 `notInLibrary` 里返回 —— 想新增就用 `POST /api/my-words`，
    普通用户永远没有权限往公共词库 `words` 里写。
    """
    deck = await _load_deck(session, deck_id, user, write=True)
    keys = {h.strip().lower() for h in body.headwords if h and h.strip()}
    if not keys:
        raise HTTPException(400, "headwords 为空")

    own_rows = (await session.execute(
        select(UserWord.id, func.lower(UserWord.headword))
        .where(UserWord.user_id == user.id)
        .where(func.lower(UserWord.headword).in_(keys))
    )).all()
    own = {key: word_id for word_id, key in own_rows}

    rest = keys - own.keys()
    global_map: dict[str, int] = {}
    if rest:
        rows = (await session.execute(
            select(Word.id, func.lower(Word.headword)).where(func.lower(Word.headword).in_(rest))
        )).all()
        global_map = {key: word_id for word_id, key in rows}

    missing = sorted(keys - own.keys() - global_map.keys())

    linked_global = {wid for (wid,) in await session.execute(
        select(deck_words.c.word_id).where(deck_words.c.deck_id == deck_id)
    )}
    linked_private = {wid for (wid,) in await session.execute(
        select(deck_user_words.c.user_word_id).where(deck_user_words.c.deck_id == deck_id)
    )}

    added = 0
    for word_id in own.values():
        if word_id in linked_private:
            continue
        await session.execute(
            deck_user_words.insert().values(deck_id=deck_id, user_word_id=word_id)
        )
        added += 1
    for word_id in global_map.values():
        if word_id in linked_global:
            continue
        await session.execute(deck_words.insert().values(deck_id=deck_id, word_id=word_id))
        added += 1

    if added:
        deck.updated_at = datetime.now(timezone.utc)
    await session.commit()
    return DeckWordsResult(
        deck_id=deck_id,
        added=added,
        already=len(own) + len(global_map) - added,
        not_in_library=missing,
    )


@app.post("/api/decks/{deck_id}/words/remove", response_model=DeckWordsResult, tags=["decks"])
async def remove_words(
    deck_id: int, body: DeckWordsIn, user: CurrentUser, session: SessionDep
) -> DeckWordsResult:
    """把词从词表上摘掉。只删挂载，公共词库和私有词条本身都保留。"""
    deck = await _load_deck(session, deck_id, user, write=True)
    keys = {h.strip().lower() for h in body.headwords if h and h.strip()}
    if not keys:
        raise HTTPException(400, "headwords 为空")

    global_ids = [row[0] for row in (await session.execute(
        select(Word.id).where(func.lower(Word.headword).in_(keys))
    )).all()]
    own_ids = [row[0] for row in (await session.execute(
        select(UserWord.id)
        .where(UserWord.user_id == user.id)
        .where(func.lower(UserWord.headword).in_(keys))
    )).all()]

    removed = 0
    if global_ids:
        result = await session.execute(
            deck_words.delete()
            .where(deck_words.c.deck_id == deck_id)
            .where(deck_words.c.word_id.in_(global_ids))
        )
        removed += result.rowcount or 0
    if own_ids:
        result = await session.execute(
            deck_user_words.delete()
            .where(deck_user_words.c.deck_id == deck_id)
            .where(deck_user_words.c.user_word_id.in_(own_ids))
        )
        removed += result.rowcount or 0

    if removed:
        deck.updated_at = datetime.now(timezone.utc)
    await session.commit()
    return DeckWordsResult(deck_id=deck_id, removed=removed)


def _user_word_out(word: UserWord) -> WordOut:
    return WordOut(
        id=word.id,
        source="private",
        headword=word.headword,
        phonetic=word.phonetic,
        pos=word.part_of_speech,
        meaning=word.meanings,
        mnemonic=word.mnemonic,
        root=word.roots,
        derivation=word.derivations,
        example=word.examples,
        synonym=word.synonyms,
        tags=word.tags,
        frequency=word.frequency,
    )


def _apply_user_word_changes(word: UserWord, changes: dict) -> None:
    """把 PATCH 里出现过的字段写到 UserWord 上（`headword` 由调用方先校验）。"""
    for field, value in changes.items():
        column = _MY_WORD_FIELDS.get(field)
        if column is None or field == "headword":
            continue
        setattr(word, column, value)


async def _headword_taken(
    session: AsyncSession, user_id, headword: str, exclude_id: int
) -> bool:
    return (
        await session.scalar(
            select(UserWord.id)
            .where(UserWord.user_id == user_id)
            .where(func.lower(UserWord.headword) == headword.lower())
            .where(UserWord.id != exclude_id)
        )
        is not None
    )


@app.patch("/api/decks/{deck_id}/words/{word_id}", response_model=WordOut, tags=["decks"])
async def edit_deck_word(
    deck_id: int,
    word_id: int,
    body: MyWordUpdate,
    user: CurrentUser,
    session: SessionDep,
    source: Literal["global", "private"] = Query(
        "global", description="这个词来自公共词库还是你的私有词条"
    ),
) -> WordOut:
    """改词表里的一个词。

    公共词库（`source=global`）**不允许直接改**：第一次修改会复制一份私有词条
    （copy-on-write），并把**这张词表**里对它的挂载换成私有词。之后改的都是你自己那份，
    公共词库、别人词表里对同一个词的引用都不受影响。

    `source=private` 就是改你自己的私有词条，原地更新。
    """
    deck = await _load_deck(session, deck_id, user, write=True)
    changes = body.model_dump(exclude_unset=True)

    if source == "private":
        word = await session.get(UserWord, word_id)
        if word is None or word.user_id != user.id:
            raise HTTPException(404, f"词条 {word_id} 不存在")
        mounted = await session.scalar(
            select(func.count())
            .select_from(deck_user_words)
            .where(deck_user_words.c.deck_id == deck_id)
            .where(deck_user_words.c.user_word_id == word_id)
        )
        if not mounted:
            raise HTTPException(404, "这个词不在这张词表里")
        if "headword" in changes:
            headword = (changes["headword"] or "").strip()
            if not headword:
                raise HTTPException(400, "单词不能为空")
            if await _headword_taken(session, user.id, headword, word.id):
                raise HTTPException(409, f"你的私有词库里已经有「{headword}」了")
            word.headword = headword
        _apply_user_word_changes(word, changes)
        word.updated_at = datetime.now(timezone.utc)
        await session.commit()
        await session.refresh(word)
        return _user_word_out(word)

    original = await session.get(Word, word_id)
    if original is None:
        raise HTTPException(404, f"词条 {word_id} 不存在")
    mounted_global = await session.scalar(
        select(func.count())
        .select_from(deck_words)
        .where(deck_words.c.deck_id == deck_id)
        .where(deck_words.c.word_id == word_id)
    )
    if not mounted_global:
        raise HTTPException(404, "这个词不在这张词表里")

    # 已经有同名私有词就直接复用，避免同一个人存两份
    private = await session.scalar(
        select(UserWord)
        .where(UserWord.user_id == user.id)
        .where(func.lower(UserWord.headword) == original.headword.lower())
    )
    if private is None:
        private = UserWord(
            user_id=user.id,
            headword=original.headword,
            phonetic=original.phonetic,
            part_of_speech=original.part_of_speech,
            meanings=original.meanings,
            mnemonic=original.mnemonic,
            roots=original.roots,
            derivations=original.derivations,
            examples=original.examples,
            synonyms=original.synonyms,
            tags=original.tags,
            frequency=original.frequency,
        )
        session.add(private)
        await session.flush()

    if "headword" in changes:
        headword = (changes["headword"] or "").strip()
        if not headword:
            raise HTTPException(400, "单词不能为空")
        if await _headword_taken(session, user.id, headword, private.id):
            raise HTTPException(409, f"你的私有词库里已经有「{headword}」了")
        private.headword = headword
    _apply_user_word_changes(private, changes)
    private.updated_at = datetime.now(timezone.utc)

    # 换挂载：这张表里的公共词摘掉，换成私有词
    await session.execute(
        deck_words.delete()
        .where(deck_words.c.deck_id == deck_id)
        .where(deck_words.c.word_id == word_id)
    )
    already_private = await session.scalar(
        select(func.count())
        .select_from(deck_user_words)
        .where(deck_user_words.c.deck_id == deck_id)
        .where(deck_user_words.c.user_word_id == private.id)
    )
    if not already_private:
        await session.execute(
            deck_user_words.insert().values(deck_id=deck_id, user_word_id=private.id)
        )
    deck.updated_at = datetime.now(timezone.utc)
    await session.commit()
    await session.refresh(private)
    return _user_word_out(private)


@app.post("/api/decks/{deck_id}/import", response_model=ImportResult, tags=["admin"])
async def import_into_deck(
    deck_id: int,
    user: CurrentUser,
    session: SessionDep,
    file: Annotated[UploadFile, File(description="CSV 或 JSON，格式同 App 导入格式")],
    replace: bool = Query(False, description="先清空这张词表的挂载，再按文件重建"),
) -> ImportResult:
    """导入会写公共词库 `words`，所以**只有管理员**能调。"""
    if not user.is_admin:
        raise HTTPException(403, "导入会修改公共词库，只有管理员可以操作")
    deck = await _load_deck(session, deck_id, user, write=True)

    data = await file.read()
    limit = settings.max_upload_mb * 1024 * 1024
    if len(data) > limit:
        raise HTTPException(413, f"文件超过 {settings.max_upload_mb} MB")
    try:
        rows = parse_upload(file.filename or "", data)
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
    if not rows:
        raise HTTPException(400, "文件里没有可用的行（表头要能识别出单词列）")

    stats = await import_rows(session, deck, rows, replace_links=replace)
    await session.commit()
    return ImportResult(deck_id=deck.id, name=deck.name, **stats.as_dict())


# ---------------------------------------------------------------- 私有词条


def _my_word_hit(word: UserWord) -> WordSearchHit:
    return WordSearchHit(
        id=word.id,
        source="private",
        headword=word.headword,
        phonetic=word.phonetic,
        pos=word.part_of_speech,
        meaning=word.meanings,
        frequency=word.frequency,
    )


# 前端提交的字段名 → 模型列名
_MY_WORD_FIELDS = {
    "headword": "headword",
    "phonetic": "phonetic",
    "pos": "part_of_speech",
    "meaning": "meanings",
    "mnemonic": "mnemonic",
    "root": "roots",
    "derivation": "derivations",
    "example": "examples",
    "synonym": "synonyms",
    "tags": "tags",
    "frequency": "frequency",
}


async def _get_my_word(session: AsyncSession, word_id: int, user: User) -> UserWord:
    word = await session.get(UserWord, word_id)
    # 别人的私有词条一律当作不存在
    if word is None or word.user_id != user.id:
        raise HTTPException(404, f"词条 {word_id} 不存在")
    return word


async def _ensure_unique_headword(
    session: AsyncSession, user: User, headword: str, exclude_id: int | None = None
) -> None:
    stmt = (
        select(UserWord.id)
        .where(UserWord.user_id == user.id)
        .where(func.lower(UserWord.headword) == headword.lower())
    )
    if exclude_id is not None:
        stmt = stmt.where(UserWord.id != exclude_id)
    if await session.scalar(stmt) is not None:
        raise HTTPException(409, f"你的私有词库里已经有「{headword}」了")


@app.get("/api/my-words", response_model=WordSearchPage, tags=["my-words"])
async def list_my_words(
    user: CurrentUser,
    session: SessionDep,
    q: str | None = Query(None, description="按词头或释义搜"),
    offset: int = Query(0, ge=0),
    limit: int | None = Query(None, ge=1, le=settings.page_size_max),
) -> WordSearchPage:
    """只列自己的私有词条。公共词库请用 `/api/words/search`。"""
    size = limit or settings.page_size_default
    stmt = select(UserWord).where(UserWord.user_id == user.id)
    if q:
        pattern = f"%{q.strip()}%"
        stmt = stmt.where(
            UserWord.headword.ilike(pattern)
            | func.coalesce(UserWord.meanings, "").ilike(pattern)
        )
    total = await session.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    rows = (await session.scalars(
        stmt.order_by(
            case((UserWord.frequency <= 0, 1), else_=0),
            UserWord.frequency,
            func.lower(UserWord.headword),
        ).offset(offset).limit(size)
    )).all()
    return WordSearchPage(total=total, items=[_my_word_hit(row) for row in rows])


@app.post("/api/my-words", response_model=WordSearchHit, status_code=201, tags=["my-words"])
async def create_my_word(
    body: MyWordIn, user: CurrentUser, session: SessionDep
) -> WordSearchHit:
    """新建一条私有词条。不会碰公共词库 `words`。"""
    headword = body.headword.strip()
    await _ensure_unique_headword(session, user, headword)

    word = UserWord(user_id=user.id, headword=headword)
    for field, column in _MY_WORD_FIELDS.items():
        if field == "headword":
            continue
        setattr(word, column, getattr(body, field))
    session.add(word)
    await session.commit()
    await session.refresh(word)
    return _my_word_hit(word)


@app.patch("/api/my-words/{word_id}", response_model=WordSearchHit, tags=["my-words"])
async def update_my_word(
    word_id: int, body: MyWordUpdate, user: CurrentUser, session: SessionDep
) -> WordSearchHit:
    word = await _get_my_word(session, word_id, user)
    changes = body.model_dump(exclude_unset=True)
    if "headword" in changes:
        headword = (changes["headword"] or "").strip()
        if not headword:
            raise HTTPException(400, "单词不能为空")
        await _ensure_unique_headword(session, user, headword, exclude_id=word.id)
    for field, value in changes.items():
        setattr(word, _MY_WORD_FIELDS[field], value)
    word.updated_at = datetime.now(timezone.utc)
    await session.commit()
    await session.refresh(word)
    return _my_word_hit(word)


@app.delete("/api/my-words/{word_id}", status_code=204, tags=["my-words"])
async def delete_my_word(word_id: int, user: CurrentUser, session: SessionDep) -> Response:
    word = await _get_my_word(session, word_id, user)
    await session.delete(word)
    await session.commit()
    return Response(status_code=204)


@app.post(
    "/api/decks/{deck_id}/private-words/import",
    response_model=ImportResult,
    tags=["my-words"],
)
async def import_private_words(
    deck_id: int,
    user: CurrentUser,
    session: SessionDep,
    file: Annotated[UploadFile, File(description="CSV 或 JSON，格式同 App 导入格式")],
    replace: bool = Query(False, description="先清空这张词表的私有词挂载再导入"),
) -> ImportResult:
    """把自己的 CSV 传上来，写进 `user_words` 并挂到这张词表。

    和 `/import` 的区别：那条只给管理员、写公共词库；这条任何人对自己词表可用，
    只写自己的私有词条，永远不碰 `words`。
    """
    deck = await _load_deck(session, deck_id, user, write=True)
    data = await file.read()
    limit = settings.max_upload_mb * 1024 * 1024
    if len(data) > limit:
        raise HTTPException(413, f"文件超过 {settings.max_upload_mb} MB")
    try:
        rows = parse_upload(file.filename or "", data)
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
    if not rows:
        raise HTTPException(400, "文件里没有可用的行（表头要能识别出单词列）")

    stats = await import_user_words(session, user.id, deck, rows, replace_links=replace)
    await session.commit()
    return ImportResult(deck_id=deck.id, name=deck.name, **stats.as_dict())


@app.get("/api/words/search", response_model=WordSearchPage, tags=["my-words"])
async def search_words(
    user: CurrentUser,
    session: SessionDep,
    q: str = Query(..., min_length=1, description="词头或释义片段"),
    limit: int = Query(50, ge=1, le=500),
    deck_id: int | None = Query(
        None, alias="deckId", description="传了就把「是否已在该词表」一起返回"
    ),
) -> WordSearchPage:
    """给「往词表里加词」用的搜索：公共词库 + 自己的私有词条。

    同一个拼写两边都有时都会返回，前端按 `source` 区分（私有的大概率是你改过的版本）。
    """
    needle = q.strip()
    pattern = f"%{needle}%"

    global_part = (
        select(
            Word.id.label("id"),
            literal("global", String).label("source"),
            Word.headword.label("headword"),
            Word.phonetic.label("phonetic"),
            Word.part_of_speech.label("part_of_speech"),
            Word.meanings.label("meanings"),
            Word.frequency.label("frequency"),
        )
        .where(or_(Word.headword.ilike(pattern), func.coalesce(Word.meanings, "").ilike(pattern)))
    )
    private_part = (
        select(
            UserWord.id.label("id"),
            literal("private", String).label("source"),
            UserWord.headword.label("headword"),
            UserWord.phonetic.label("phonetic"),
            UserWord.part_of_speech.label("part_of_speech"),
            UserWord.meanings.label("meanings"),
            UserWord.frequency.label("frequency"),
        )
        .where(UserWord.user_id == user.id)
        .where(
            or_(
                UserWord.headword.ilike(pattern),
                func.coalesce(UserWord.meanings, "").ilike(pattern),
            )
        )
    )
    entries = union_all(global_part, private_part).subquery("hits")

    total = await session.scalar(select(func.count()).select_from(entries)) or 0
    lower_head = func.lower(entries.c.headword)
    rows = (await session.execute(
        select(entries)
        .order_by(
            # 完全相同 → 前缀命中 → 其余；同组内私有词优先，再按词频
            case((lower_head == needle.lower(), 0), (lower_head.startswith(needle.lower()), 1), else_=2),
            case((entries.c.source == "private", 0), else_=1),
            case((entries.c.frequency <= 0, 1), else_=0),
            entries.c.frequency,
            lower_head,
        )
        .limit(limit)
    )).all()
    # 词表若在，再查一次这几十个词的挂载情况，避免把 union 写得更复杂
    in_deck: set[tuple[str, int]] = set()
    if deck_id is not None:
        await _load_deck(session, deck_id, user, write=False)
        keys = [row.headword.lower() for row in rows]
        if keys:
            global_ids = {wid for (wid,) in await session.execute(
                select(deck_words.c.word_id).where(deck_words.c.deck_id == deck_id)
            )}
            private_ids = {wid for (wid,) in await session.execute(
                select(deck_user_words.c.user_word_id).where(deck_user_words.c.deck_id == deck_id)
            )}
            for row in rows:
                hit_ids = global_ids if row.source == "global" else private_ids
                if row.id in hit_ids:
                    in_deck.add((row.source, row.id))

    return WordSearchPage(
        total=total,
        items=[
            WordSearchHit(
                id=row.id,
                source=row.source,
                headword=row.headword,
                phonetic=row.phonetic,
                pos=row.part_of_speech,
                meaning=row.meanings,
                frequency=row.frequency,
                in_deck=(row.source, row.id) in in_deck,
            )
            for row in rows
        ],
    )


# ---------------------------------------------------------------- 进度同步


@app.post("/api/sync/push", response_model=SyncPushResult, tags=["sync"])
async def sync_push(request: SyncPush, user: CurrentUser, session: SessionDep) -> SyncPushResult:
    """推送增量。所有行都带客户端的 `updatedAt`，服务端按「后写胜出」合并。"""
    total = (
        len(request.states) + len(request.logs) + len(request.tags)
        + len(request.word_tags) + len(request.flags) + len(request.settings)
    )
    if total > settings.sync_max_rows:
        raise HTTPException(
            413,
            f"一次最多推 {settings.sync_max_rows} 行，这次是 {total} 行，请分批",
        )
    return await sync_service.push(session, request, user.id)


@app.get("/api/sync/pull", response_model=SyncPull, tags=["sync"])
async def sync_pull(
    user: CurrentUser,
    session: SessionDep,
    since: int = Query(0, ge=0, description="上次拿到的 revision，首次传 0"),
    limit: int | None = Query(None, ge=1, le=5000, description="每张表最多返回多少行"),
) -> SyncPull:
    """拉取增量。返回的 `revision` 就是下次的 `since`；`hasMore` 为真时接着拉。"""
    return await sync_service.pull(session, since, limit or settings.sync_page_limit, user.id)


@app.get("/api/sync/status", response_model=SyncStatus, tags=["sync"])
async def sync_status(user: CurrentUser, session: SessionDep) -> SyncStatus:
    return await sync_service.status(session, user.id)


# ---------------------------------------------------------------- 浏览器里看


def _local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))
            return probe.getsockname()[0]
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"


INDEX_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>词表服务</title>
<style>
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body { margin:0; padding:28px 18px 60px; background:#f6f7f9; color:#1b1f24;
         font:15px/1.6 -apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif; }
  .wrap { max-width: 880px; margin: 0 auto; }
  h1 { font-size:21px; margin:0 0 4px; }
  .sub { color:#5f6b7a; font-size:13px; }
  .addr { display:inline-block; margin-top:10px; background:#fff; border:1px solid #dde2e8;
          border-radius:8px; padding:5px 10px; font-family:ui-monospace,Consolas,monospace; font-size:13px; }
  form { background:#fff; border:1px solid #e3e7ec; border-radius:12px; padding:16px; margin-top:18px; }
  label { display:block; font-size:12px; color:#5f6b7a; margin:10px 0 4px; }
  input { width:100%; padding:9px 10px; border:1px solid #dde2e8; border-radius:8px; font-size:14px; }
  .row { display:flex; gap:10px; align-items:center; margin-top:14px; }
  button { background:#07c187; color:#fff; border:0; border-radius:8px; padding:9px 16px;
           font-size:14px; cursor:pointer; }
  button.ghost { background:#eef1f5; color:#3d4753; }
  .err { color:#c62828; font-size:13px; margin-top:8px; }
  .hint { color:#7b8794; font-size:12px; margin-top:10px; line-height:1.5; }
  .bar { display:flex; justify-content:space-between; align-items:center; margin-top:18px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:14px; margin-top:14px; }
  .card { background:#fff; border:1px solid #e3e7ec; border-radius:12px; padding:14px 16px; }
  .card:hover { border-color:#07c187; }
  .name { font-weight:600; }
  .meta { color:#5f6b7a; font-size:12px; margin-top:2px; }
  .tag { display:inline-block; background:#e8f8f1; color:#0a7f5c; border-radius:5px;
         padding:0 6px; font-size:11px; margin-left:6px; }
  .tag.own { background:#eaf1ff; color:#2c5cc5; }
  .actions { display:flex; gap:8px; margin-top:12px; }
  .btn { flex:1; text-align:center; background:#07c187; color:#fff; border-radius:8px;
         padding:6px 0; font-size:13px; text-decoration:none; cursor:pointer; border:0; }
  .btn.ghost { background:#eef1f5; color:#3d4753; }
  .hide { display:none; }
</style></head><body><div class="wrap">
  <h1>词表服务</h1>
  <div class="sub" id="addr"></div>

  <form id="login-form">
    <div class="sub">登录后可以下载词表；自己的词表可在 App 里管理。</div>
    <label>用户名</label><input id="username" autocomplete="username" minlength="3" maxlength="64" required>
    <label>密码</label><input id="password" type="password" autocomplete="current-password" minlength="6" maxlength="128" required>
    <div class="hint">用户名 3–64 位、密码至少 6 位。第一次使用直接点「注册」，第一个账号会成为管理员。</div>
    <div class="row"><button type="submit" id="submit">登录</button>
      <button type="button" class="ghost" id="to-register">注册</button></div>
    <div class="err" id="err"></div>
  </form>

  <div id="panel" class="hide">
    <div class="bar">
      <div class="sub" id="who"></div>
      <button class="ghost" id="logout">退出</button>
    </div>
    <div class="grid" id="decks"></div>
  </div>
</div>
<script>
const $ = (id) => document.getElementById(id);
const state = { token: localStorage.getItem("vs_token") || "" };

$("addr").textContent = "服务地址：http://" + location.host;

function setError(msg) { $("err").textContent = msg || ""; }

// FastAPI 的校验错误是数组（[{loc,msg,type}]），直接拼进 textContent 会变成
// [object Object]。这里统一取成人话：字符串直接用，数组取每条的 msg。
function detailMessage(data, fallback) {
  if (!data) return fallback;
  const detail = data.detail;
  if (typeof detail === "string") return detail;
  if (Array.isArray(detail)) {
    const parts = detail.map((item) => {
      if (!item) return "";
      if (typeof item === "string") return item;
      const where = Array.isArray(item.loc) ? item.loc.slice(1).join(".") : "";
      return (where ? where + "：" : "") + (item.msg || JSON.stringify(item));
    }).filter(Boolean);
    return parts.join("；") || fallback;
  }
  if (detail && typeof detail === "object") return detail.msg || fallback;
  return fallback;
}

async function api(path, options = {}) {
  const headers = Object.assign({}, options.headers || {});
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  if (options.body && typeof options.body === "string") headers["Content-Type"] = "application/json";
  const res = await fetch(path, Object.assign({}, options, { headers }));
  if (res.status === 401) { logout(); throw new Error("登录已过期"); }
  if (!res.ok) {
    let payload = null;
    try { payload = await res.json(); } catch (e) {}
    throw new Error(detailMessage(payload, "请求失败（HTTP " + res.status + "）"));
  }
  return res;
}

function logout() {
  state.token = "";
  localStorage.removeItem("vs_token");
  $("panel").classList.add("hide");
  $("login-form").classList.remove("hide");
}

async function login(registerMode) {
  setError("");
  const username = $("username").value.trim();
  const password = $("password").value;
  if (username.length < 3) { setError("用户名至少 3 个字符"); return; }
  if (password.length < 6) { setError("密码至少 6 个字符"); return; }

  $("submit").disabled = true;
  try {
    const res = await fetch(registerMode ? "/api/auth/register" : "/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    let data = null;
    try { data = await res.json(); } catch (e) {}
    if (!res.ok) throw new Error(detailMessage(data, "请求失败（HTTP " + res.status + "）"));
    if (!data || !data.accessToken) throw new Error("服务返回的数据不完整");
    state.token = data.accessToken;
    localStorage.setItem("vs_token", state.token);
    $("password").value = "";
    await loadDecks(data.user);
  } catch (e) {
    setError(e.message || "登录失败");
  } finally {
    $("submit").disabled = false;
  }
}

async function loadDecks(user) {
  if (!user) {
    const me = await (await api("/api/auth/me")).json();
    user = me;
  }
  $("who").textContent = user.username + (user.role === "admin" ? "（管理员）" : "");
  $("login-form").classList.add("hide");
  $("panel").classList.remove("hide");

  const decks = await (await api("/api/decks")).json();
  const host = $("decks");
  host.innerHTML = "";
  if (!decks.length) { host.innerHTML = '<div class="sub">还没有可见的词表。</div>'; return; }
  for (const d of decks) {
    const card = document.createElement("div");
    card.className = "card";
    card.innerHTML =
      '<div class="name">' + d.name +
      (d.official ? '<span class="tag">官方</span>' : '<span class="tag own">我的</span>') + '</div>' +
      '<div class="meta">' + d.wordCount + ' 词 · 更新于 ' + (d.updatedAt || "").slice(0, 10) + '</div>';
    const actions = document.createElement("div");
    actions.className = "actions";
    for (const format of ["csv", "json"]) {
      const btn = document.createElement("button");
      btn.className = "btn" + (format === "json" ? " ghost" : "");
      btn.textContent = format.toUpperCase();
      btn.onclick = () => download(d.id, d.name, format);
      actions.appendChild(btn);
    }
    card.appendChild(actions);
    host.appendChild(card);
  }
}

async function download(id, name, format) {
  const res = await api(`/api/decks/${id}/export?format=${format}`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = name + "." + format; a.click();
  URL.revokeObjectURL(url);
}

$("login-form").addEventListener("submit", (e) => { e.preventDefault(); login(false); });
$("to-register").onclick = () => login(true);
$("logout").onclick = logout;
if (state.token) loadDecks().catch(() => logout());
</script></body></html>"""


@app.get("/", response_class=HTMLResponse, include_in_schema=False)
async def index() -> HTMLResponse:
    return HTMLResponse(INDEX_HTML)
