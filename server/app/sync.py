"""学习进度同步：增量推送 + 增量拉取。

三条规则：

* **按账号隔离**：每条 SQL 都带 `user_id`，主键也是 `(user_id, ...)` 复合键。
  越权在数据层就写不进去，不靠上层 if 判断。
* **状态类走 LWW**：比较客户端给的 `updatedAt`，只有更新的才覆盖；相同时不动，
  保证重复推送幂等。
* **日志只追加**：`(user_id, clientLogId)` 唯一，重复的静默丢弃，永远不改历史。

每写过一行都会 nextval 一次 `sync_revision`，客户端只要记住上一次拉到的最大
`revision`，下次带 `since=` 就能拿到全部增量 —— 不用给每张表各维护一个游标。
"""

from __future__ import annotations

import uuid
from collections.abc import Iterable, Sequence
from typing import Any

from sqlalchemy import func, literal_column, select, text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from .models import (
    Device,
    ReviewLog,
    Setting,
    UserTag,
    WordFlag,
    WordState,
    WordUserTag,
)
from .schemas import (
    DeviceOut,
    FlagIn,
    FlagOut,
    LogIn,
    LogOut,
    SettingIn,
    SettingOut,
    StateIn,
    StateOut,
    SyncPull,
    SyncPush,
    SyncPushResult,
    SyncStatus,
    TagIn,
    TagOut,
    WordTagIn,
    WordTagOut,
)

NEXT_REVISION = literal_column("nextval('sync_revision')")

CHUNK = 500


def _chunks(items: Sequence[Any], size: int = CHUNK) -> Iterable[Sequence[Any]]:
    for start in range(0, len(items), size):
        yield items[start:start + size]


def _key(headword: str) -> str:
    return headword.strip().lower()


def _uuid(value: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(str(value))
    except (ValueError, AttributeError, TypeError):
        return None


async def _touch_device(session: AsyncSession, request: SyncPush, user_id: uuid.UUID) -> None:
    stmt = pg_insert(Device).values(
        user_id=user_id,
        id=request.device_id,
        name=request.device_name,
        platform=request.platform,
        last_seen_at=func.now(),
    )
    stmt = stmt.on_conflict_do_update(
        index_elements=[Device.user_id, Device.id],
        set_={
            # 客户端没报名字时不要把已有名字冲掉
            "name": func.coalesce(stmt.excluded.name, Device.name),
            "platform": func.coalesce(stmt.excluded.platform, Device.platform),
            "last_seen_at": func.now(),
        },
    )
    await session.execute(stmt)


async def _push_states(
    session: AsyncSession, rows: Sequence[StateIn], device_id: str, user_id: uuid.UUID
) -> tuple[int, int]:
    latest: dict[str, StateIn] = {}
    skipped = 0
    for row in rows:
        key = _key(row.headword)
        if not key:
            skipped += 1
            continue
        previous = latest.get(key)
        if previous is None or row.updated_at >= previous.updated_at:
            latest[key] = row
    if not latest:
        return 0, skipped

    applied = 0
    for chunk in _chunks(list(latest.items())):
        values = [
            {
                "user_id": user_id,
                "headword_key": key,
                "headword": row.headword.strip(),
                "state": row.state,
                "ease_factor": row.ease_factor,
                "interval_days": row.interval_days,
                "repetitions": row.repetitions,
                "learning_step": row.learning_step,
                "due_at_minutes": row.due_at_minutes,
                "last_reviewed_at": row.last_reviewed_at,
                "lapses": row.lapses,
                "updated_at": row.updated_at,
                "device_id": device_id,
            }
            for key, row in chunk
        ]
        stmt = pg_insert(WordState).values(values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[WordState.user_id, WordState.headword_key],
            set_={
                "headword": stmt.excluded.headword,
                "state": stmt.excluded.state,
                "ease_factor": stmt.excluded.ease_factor,
                "interval_days": stmt.excluded.interval_days,
                "repetitions": stmt.excluded.repetitions,
                "learning_step": stmt.excluded.learning_step,
                "due_at_minutes": stmt.excluded.due_at_minutes,
                "last_reviewed_at": stmt.excluded.last_reviewed_at,
                "lapses": stmt.excluded.lapses,
                "updated_at": stmt.excluded.updated_at,
                "device_id": stmt.excluded.device_id,
                "revision": NEXT_REVISION,
            },
            # 严格小于：相同时间戳重推不会产生新版本，推送幂等
            where=WordState.updated_at < stmt.excluded.updated_at,
        ).returning(WordState.headword_key)
        applied += len((await session.execute(stmt)).all())
    return applied, skipped


async def _push_logs(
    session: AsyncSession, rows: Sequence[LogIn], device_id: str, user_id: uuid.UUID
) -> tuple[int, int]:
    seen: dict[uuid.UUID, LogIn] = {}
    skipped = 0
    for row in rows:
        key = _uuid(row.client_log_id)
        if key is None or not _key(row.headword):
            skipped += 1
            continue
        seen.setdefault(key, row)
    if not seen:
        return 0, skipped

    applied = 0
    for chunk in _chunks(list(seen.items())):
        values = [
            {
                "user_id": user_id,
                "client_log_id": log_id,
                "headword_key": _key(row.headword),
                "headword": row.headword.strip(),
                "rating": row.rating,
                "reviewed_at": row.reviewed_at,
                "elapsed_ms": row.elapsed_ms,
                "interval_days_after": row.interval_days_after,
                "device_id": device_id,
            }
            for log_id, row in chunk
        ]
        stmt = (
            pg_insert(ReviewLog)
            .values(values)
            # 唯一索引是 (user_id, client_log_id)
            .on_conflict_do_nothing(index_elements=[ReviewLog.user_id, ReviewLog.client_log_id])
            .returning(ReviewLog.id)
        )
        applied += len((await session.execute(stmt)).all())
    return applied, skipped


async def _push_tags(
    session: AsyncSession, rows: Sequence[TagIn], user_id: uuid.UUID
) -> tuple[int, int]:
    latest: dict[str, TagIn] = {}
    skipped = 0
    for row in rows:
        name = row.name.strip()
        if not name:
            skipped += 1
            continue
        previous = latest.get(name)
        if previous is None or row.updated_at >= previous.updated_at:
            latest[name] = row
    if not latest:
        return 0, skipped

    applied = 0
    for chunk in _chunks(list(latest.items())):
        values = [
            {"user_id": user_id, "name": name, "updated_at": row.updated_at, "deleted": row.deleted}
            for name, row in chunk
        ]
        stmt = pg_insert(UserTag).values(values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserTag.user_id, UserTag.name],
            set_={
                "updated_at": stmt.excluded.updated_at,
                "deleted": stmt.excluded.deleted,
                "revision": NEXT_REVISION,
            },
            where=UserTag.updated_at < stmt.excluded.updated_at,
        ).returning(UserTag.name)
        applied += len((await session.execute(stmt)).all())
    return applied, skipped


async def _push_word_tags(
    session: AsyncSession, rows: Sequence[WordTagIn], user_id: uuid.UUID
) -> tuple[int, int]:
    latest: dict[tuple[str, str], WordTagIn] = {}
    skipped = 0
    for row in rows:
        key = _key(row.headword)
        name = row.tag_name.strip()
        if not key or not name:
            skipped += 1
            continue
        previous = latest.get((key, name))
        if previous is None or row.updated_at >= previous.updated_at:
            latest[(key, name)] = row
    if not latest:
        return 0, skipped

    applied = 0
    for chunk in _chunks(list(latest.items())):
        values = [
            {
                "user_id": user_id,
                "headword_key": key,
                "tag_name": name,
                "updated_at": row.updated_at,
                "deleted": row.deleted,
            }
            for (key, name), row in chunk
        ]
        stmt = pg_insert(WordUserTag).values(values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[
                WordUserTag.user_id,
                WordUserTag.headword_key,
                WordUserTag.tag_name,
            ],
            set_={
                "updated_at": stmt.excluded.updated_at,
                "deleted": stmt.excluded.deleted,
                "revision": NEXT_REVISION,
            },
            where=WordUserTag.updated_at < stmt.excluded.updated_at,
        ).returning(WordUserTag.headword_key)
        applied += len((await session.execute(stmt)).all())
    return applied, skipped


async def _push_flags(
    session: AsyncSession, rows: Sequence[FlagIn], device_id: str, user_id: uuid.UUID
) -> tuple[int, int]:
    latest: dict[str, FlagIn] = {}
    skipped = 0
    for row in rows:
        key = _key(row.headword)
        if not key:
            skipped += 1
            continue
        previous = latest.get(key)
        if previous is None or row.updated_at >= previous.updated_at:
            latest[key] = row
    if not latest:
        return 0, skipped

    applied = 0
    for chunk in _chunks(list(latest.items())):
        values = [
            {
                "user_id": user_id,
                "headword_key": key,
                "priority": row.priority,
                "updated_at": row.updated_at,
                "device_id": device_id,
            }
            for key, row in chunk
        ]
        stmt = pg_insert(WordFlag).values(values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[WordFlag.user_id, WordFlag.headword_key],
            set_={
                "priority": stmt.excluded.priority,
                "updated_at": stmt.excluded.updated_at,
                "device_id": stmt.excluded.device_id,
                "revision": NEXT_REVISION,
            },
            where=WordFlag.updated_at < stmt.excluded.updated_at,
        ).returning(WordFlag.headword_key)
        applied += len((await session.execute(stmt)).all())
    return applied, skipped


async def _push_settings(
    session: AsyncSession, rows: Sequence[SettingIn], user_id: uuid.UUID
) -> tuple[int, int]:
    latest: dict[str, SettingIn] = {}
    skipped = 0
    for row in rows:
        key = row.key.strip()
        if not key:
            skipped += 1
            continue
        previous = latest.get(key)
        if previous is None or row.updated_at >= previous.updated_at:
            latest[key] = row
    if not latest:
        return 0, skipped

    applied = 0
    for chunk in _chunks(list(latest.items())):
        values = [
            {"user_id": user_id, "key": key, "value": row.value, "updated_at": row.updated_at}
            for key, row in chunk
        ]
        stmt = pg_insert(Setting).values(values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[Setting.user_id, Setting.key],
            set_={
                "value": stmt.excluded.value,
                "updated_at": stmt.excluded.updated_at,
                "revision": NEXT_REVISION,
            },
            where=Setting.updated_at < stmt.excluded.updated_at,
        ).returning(Setting.key)
        applied += len((await session.execute(stmt)).all())
    return applied, skipped


async def current_revision(session: AsyncSession) -> int:
    value = await session.scalar(text("SELECT last_value FROM sync_revision"))
    return int(value or 0)


async def push(session: AsyncSession, request: SyncPush, user_id: uuid.UUID) -> SyncPushResult:
    await _touch_device(session, request, user_id)

    applied: dict[str, int] = {}
    skipped: dict[str, int] = {}

    applied["states"], skipped["states"] = await _push_states(
        session, request.states, request.device_id, user_id
    )
    applied["logs"], skipped["logs"] = await _push_logs(
        session, request.logs, request.device_id, user_id
    )
    applied["tags"], skipped["tags"] = await _push_tags(session, request.tags, user_id)
    applied["wordTags"], skipped["wordTags"] = await _push_word_tags(
        session, request.word_tags, user_id
    )
    applied["flags"], skipped["flags"] = await _push_flags(
        session, request.flags, request.device_id, user_id
    )
    applied["settings"], skipped["settings"] = await _push_settings(
        session, request.settings, user_id
    )

    await session.commit()
    return SyncPushResult(
        revision=await current_revision(session),
        applied={k: v for k, v in applied.items() if v},
        skipped={k: v for k, v in skipped.items() if v},
    )


async def _fetch(
    session: AsyncSession, model, since: int, limit: int, user_id: uuid.UUID
) -> tuple[list, bool]:
    stmt = (
        select(model)
        .where(model.user_id == user_id)
        .where(model.revision > since)
        .order_by(model.revision)
        .limit(limit + 1)  # 多取一条用来判断还有没有下一页
    )
    rows = list(await session.scalars(stmt))
    return rows[:limit], len(rows) > limit


async def pull(
    session: AsyncSession, since: int, limit: int, user_id: uuid.UUID
) -> SyncPull:
    states, more_states = await _fetch(session, WordState, since, limit, user_id)
    logs, more_logs = await _fetch(session, ReviewLog, since, limit, user_id)
    tags, more_tags = await _fetch(session, UserTag, since, limit, user_id)
    word_tags, more_word_tags = await _fetch(session, WordUserTag, since, limit, user_id)
    flags, more_flags = await _fetch(session, WordFlag, since, limit, user_id)
    settings, more_settings = await _fetch(session, Setting, since, limit, user_id)

    revisions = [
        row.revision
        for group in (states, logs, tags, word_tags, flags, settings)
        for row in group
    ]
    revision = max(revisions) if revisions else since

    return SyncPull(
        revision=revision,
        has_more=any([more_states, more_logs, more_tags, more_word_tags, more_flags, more_settings]),
        states=[
            StateOut(
                headword=row.headword,
                updated_at=row.updated_at,
                state=row.state,
                ease_factor=row.ease_factor,
                interval_days=row.interval_days,
                repetitions=row.repetitions,
                learning_step=row.learning_step,
                due_at_minutes=row.due_at_minutes,
                last_reviewed_at=row.last_reviewed_at,
                lapses=row.lapses,
                revision=row.revision,
                device_id=row.device_id,
            )
            for row in states
        ],
        logs=[
            LogOut(
                client_log_id=str(row.client_log_id),
                headword=row.headword,
                rating=row.rating,
                reviewed_at=row.reviewed_at,
                elapsed_ms=row.elapsed_ms,
                interval_days_after=row.interval_days_after,
                revision=row.revision,
                device_id=row.device_id,
            )
            for row in logs
        ],
        tags=[
            TagOut(name=row.name, updated_at=row.updated_at, deleted=row.deleted, revision=row.revision)
            for row in tags
        ],
        word_tags=[
            WordTagOut(
                headword=row.headword_key,
                tag_name=row.tag_name,
                updated_at=row.updated_at,
                deleted=row.deleted,
                revision=row.revision,
            )
            for row in word_tags
        ],
        flags=[
            FlagOut(
                headword=row.headword_key,
                updated_at=row.updated_at,
                priority=row.priority,
                revision=row.revision,
            )
            for row in flags
        ],
        settings=[
            SettingOut(
                key=row.key, value=row.value, updated_at=row.updated_at, revision=row.revision
            )
            for row in settings
        ],
    )


async def status(session: AsyncSession, user_id: uuid.UUID) -> SyncStatus:
    def count(model):
        return (
            select(func.count())
            .select_from(model)
            .where(model.user_id == user_id)
        )

    counts = {
        "states": await session.scalar(count(WordState)) or 0,
        "logs": await session.scalar(count(ReviewLog)) or 0,
        "tags": await session.scalar(count(UserTag)) or 0,
        "wordTags": await session.scalar(count(WordUserTag)) or 0,
        "flags": await session.scalar(count(WordFlag)) or 0,
        "settings": await session.scalar(count(Setting)) or 0,
    }
    devices = list(await session.scalars(
        select(Device).where(Device.user_id == user_id).order_by(Device.last_seen_at.desc())
    ))
    return SyncStatus(
        revision=await current_revision(session),
        counts=counts,
        devices=[
            DeviceOut(
                id=device.id, name=device.name, platform=device.platform, last_seen_at=device.last_seen_at
            )
            for device in devices
        ],
    )
