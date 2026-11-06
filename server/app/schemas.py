"""对外的 JSON 结构。

字段名用 camelCase（`wordCount` / `updatedAt`），和 App 那边的 Kotlin 属性一致；
请求侧的列名仍然是 App 导入器认的那套（word / pos / meaning …）。
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


def to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.capitalize() for part in rest)


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class DeckOut(ApiModel):
    id: int
    name: str
    description: str | None = None
    word_count: int = 0
    created_at: datetime | None = None
    updated_at: datetime | None = None
    csv_url: str
    json_url: str
    # 空 = 官方词表；有值 = 某个用户的私有词表
    owner_id: uuid.UUID | None = None
    owner_name: str | None = None
    official: bool = False
    # 这张表是不是当前登录者的（官方表恒为 false）。客户端用它分「我的 / 公开」，
    # 不要拿 ownerName 去比对 —— 改名、公开之后字段可能没带回来。
    mine: bool = False
    # private / public；官方词表恒为 public（所有人可见）
    visibility: str = "private"
    share_code: str | None = None
    # 当前登录者能不能改这张表（官方表只有管理员能改）
    editable: bool = False


class WordOut(ApiModel):
    # 词表里可能同时有公共词和自己的私有词，id 各自独立，靠 source 区分
    id: int | None = None
    source: str = "global"
    headword: str
    phonetic: str | None = None
    part_of_speech: str | None = Field(default=None, alias="pos")
    meanings: str | None = Field(default=None, alias="meaning")
    mnemonic: str | None = None
    roots: str | None = Field(default=None, alias="root")
    derivations: str | None = Field(default=None, alias="derivation")
    examples: str | None = Field(default=None, alias="example")
    synonyms: str | None = Field(default=None, alias="synonym")
    tags: str | None = None
    frequency: int = 0


class WordPage(ApiModel):
    deck_id: int
    name: str
    total: int
    offset: int
    limit: int
    items: list[WordOut]


class ImportResult(ApiModel):
    deck_id: int
    name: str
    total_rows: int
    created: int
    updated: int
    unchanged: int
    linked: int
    duplicate_lines: list[int] = []
    issues: list[str] = []


class HealthOut(ApiModel):
    status: str
    version: str
    database: str
    deck_count: int
    word_count: int


# ---------------------------------------------------------------- 进度同步


class StateIn(ApiModel):
    """一个词的记忆状态。`updated_at` 是客户端的毫秒时间戳，LWW 的唯一依据。"""

    headword: str
    updated_at: int
    state: str = "NEW"
    ease_factor: float = 2.5
    interval_days: float = 0.0
    repetitions: int = 0
    learning_step: int = 0
    due_at_minutes: int = 0
    last_reviewed_at: int | None = None
    lapses: int = 0


class LogIn(ApiModel):
    """一次作答。`client_log_id` 由客户端生成，服务端靠它去重。"""

    client_log_id: str
    headword: str
    rating: str
    reviewed_at: int
    elapsed_ms: int = 0
    interval_days_after: float = 0.0


class TagIn(ApiModel):
    name: str
    updated_at: int
    deleted: bool = False


class WordTagIn(ApiModel):
    headword: str
    tag_name: str
    updated_at: int
    deleted: bool = False


class FlagIn(ApiModel):
    headword: str
    updated_at: int
    priority: bool = False


class SettingIn(ApiModel):
    key: str
    value: str
    updated_at: int


class SyncPush(ApiModel):
    device_id: str = Field(min_length=8, max_length=64)
    device_name: str | None = Field(default=None, max_length=120)
    platform: str | None = Field(default=None, max_length=60)

    states: list[StateIn] = Field(default_factory=list)
    logs: list[LogIn] = Field(default_factory=list)
    tags: list[TagIn] = Field(default_factory=list)
    word_tags: list[WordTagIn] = Field(default_factory=list)
    flags: list[FlagIn] = Field(default_factory=list)
    settings: list[SettingIn] = Field(default_factory=list)


class SyncPushResult(ApiModel):
    revision: int
    applied: dict[str, int] = Field(default_factory=dict)
    skipped: dict[str, int] = Field(default_factory=dict)


class StateOut(StateIn):
    revision: int
    device_id: str | None = None


class LogOut(LogIn):
    revision: int
    device_id: str | None = None


class TagOut(TagIn):
    revision: int


class WordTagOut(WordTagIn):
    revision: int


class FlagOut(FlagIn):
    revision: int


class SettingOut(SettingIn):
    revision: int


class SyncPull(ApiModel):
    revision: int
    has_more: bool = False
    states: list[StateOut] = Field(default_factory=list)
    logs: list[LogOut] = Field(default_factory=list)
    tags: list[TagOut] = Field(default_factory=list)
    word_tags: list[WordTagOut] = Field(default_factory=list)
    flags: list[FlagOut] = Field(default_factory=list)
    settings: list[SettingOut] = Field(default_factory=list)


class DeviceOut(ApiModel):
    id: str
    name: str | None = None
    platform: str | None = None
    last_seen_at: datetime | None = None


class SyncStatus(ApiModel):
    revision: int
    counts: dict[str, int] = Field(default_factory=dict)
    devices: list[DeviceOut] = Field(default_factory=list)

# ---------------------------------------------------------------- 账号


class UserOut(ApiModel):
    id: uuid.UUID
    username: str
    display_name: str | None = None
    role: str = "user"
    created_at: datetime | None = None
    last_login_at: datetime | None = None


class RegisterIn(ApiModel):
    username: str = Field(min_length=3, max_length=64)
    password: str = Field(min_length=6, max_length=128)
    display_name: str | None = Field(default=None, max_length=120)


class LoginIn(ApiModel):
    username: str
    password: str


class TokenOut(ApiModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime
    user: UserOut


class AdminUserCreate(ApiModel):
    username: str = Field(min_length=3, max_length=64)
    password: str = Field(min_length=6, max_length=128)
    display_name: str | None = Field(default=None, max_length=120)
    role: str = Field(default="user", pattern="^(user|admin)$")


class PasswordChange(ApiModel):
    old_password: str
    new_password: str = Field(min_length=6, max_length=128)


# ---------------------------------------------------------------- 词表写操作


class DeckCreate(ApiModel):
    name: str = Field(min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=500)
    visibility: str = Field(default="private", pattern="^(private|public)$")
    # 建表时把另一张可见词表的词**挂**过来（只建挂载，不复制词条）
    seed_from_deck_id: int | None = Field(default=None, alias="seedFromDeckId")


class DeckUpdate(ApiModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=500)
    visibility: str | None = Field(default=None, pattern="^(private|public)$")
    # 自定义分享码：4–32 位字母数字（横线空格会被去掉），只校验唯一性
    share_code: str | None = Field(default=None, min_length=4, max_length=32, alias="shareCode")


class DeckImportFrom(ApiModel):
    """把另一张词表的词挂到这张表上。"""

    source_id: int = Field(alias="sourceId")
    # replace = 先清空本表的挂载；append = 保留，只补差集
    mode: str = Field(default="append", pattern="^(replace|append)$")


class DeckImportResult(ApiModel):
    deck_id: int = Field(alias="deckId")
    source_id: int = Field(alias="sourceId")
    added: int = 0
    removed: int = 0
    skipped_private: int = 0
    total: int = 0


class DeckWordsIn(ApiModel):
    headwords: list[str] = Field(min_length=1)


class DeckWordsResult(ApiModel):
    deck_id: int
    added: int = 0
    already: int = 0
    removed: int = 0
    not_in_library: list[str] = Field(default_factory=list)

# ---------------------------------------------------------------- 私有词条


class MyWordIn(ApiModel):
    """用户自己的词条。字段名与导入格式一致（word / pos / meaning …）。"""

    headword: str = Field(min_length=1, max_length=200)
    phonetic: str | None = Field(default=None, max_length=200)
    pos: str | None = Field(default=None, max_length=60)
    meaning: str | None = None
    mnemonic: str | None = None
    root: str | None = None
    derivation: str | None = None
    example: str | None = None
    synonym: str | None = None
    tags: str | None = None
    frequency: int = Field(default=0, ge=0)


class MyWordUpdate(ApiModel):
    """全部可选：只更新请求里出现过的字段（`model_fields_set`）。"""

    headword: str | None = Field(default=None, min_length=1, max_length=200)
    phonetic: str | None = Field(default=None, max_length=200)
    pos: str | None = Field(default=None, max_length=60)
    meaning: str | None = None
    mnemonic: str | None = None
    root: str | None = None
    derivation: str | None = None
    example: str | None = None
    synonym: str | None = None
    tags: str | None = None
    frequency: int | None = Field(default=None, ge=0)


class WordSearchHit(ApiModel):
    id: int
    source: str  # global / private
    headword: str
    phonetic: str | None = None
    part_of_speech: str | None = Field(default=None, alias="pos")
    meanings: str | None = Field(default=None, alias="meaning")
    frequency: int = 0
    # 传了 deckId 时告诉前端这个词是否已经在那张词表里
    in_deck: bool = False


class WordSearchPage(ApiModel):
    total: int
    items: list[WordSearchHit]
