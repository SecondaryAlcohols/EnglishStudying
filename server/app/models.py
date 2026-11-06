"""数据模型。

四组表：

1. **账号**：`users` —— 用户名 + Argon2 密码哈希，角色分 `admin` / `user`。
2. **词表**：`decks` / `words` / `deck_words`。
   `words` 是服务器维护的公共词库，普通用户**只读**；`decks.owner_id` 为空表示官方词表，
   非空表示某个用户的私有词表。
3. **设备**：`devices` —— 谁在同步，按账号隔离。
4. **学习进度**：`word_states` / `review_logs` / `user_tags` / `word_user_tags` /
   `word_flags` / `settings` —— 全部按 `user_id` 分租户。

进度用 `headword_key = lower(headword)` 当键：手机与服务端的词 id 各自自增，对不上。
所有同步表都带 `revision`，取值来自共享序列 `sync_revision`，客户端只记一个游标即可增量拉取。
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    DateTime,
    Float,
    ForeignKey,
    Identity,
    Index,
    Integer,
    String,
    Table,
    Text,
    Uuid,
    func,
    text,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


REVISION_DEFAULT = text("nextval('sync_revision')")


# ------------------------------------------------------------------ 账号


class User(Base):
    __tablename__ = "users"

    # PostgreSQL 18 自带 uuidv7()：时间有序，比 uuid4 的索引局部性好
    id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), primary_key=True, server_default=text("uuidv7()")
    )
    username: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    display_name: Mapped[str | None] = mapped_column(String(120))
    # admin 能改公共词库、能往任意词表导入；user 只能动自己的词表
    role: Mapped[str] = mapped_column(String(16), nullable=False, default="user")
    disabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    # passive_deletes=True：删号时交给数据库 ON DELETE CASCADE，
    # 否则 SQLAlchemy 会先把子行的 owner_id 置空（词表就变成“官方”的了）
    decks: Mapped[list["Deck"]] = relationship(
        back_populates="owner", lazy="raise", passive_deletes=True
    )

    @property
    def is_admin(self) -> bool:
        return self.role == "admin"


# ------------------------------------------------------------------ 词表


deck_words = Table(
    "deck_words",
    Base.metadata,
    Column("deck_id", BigInteger, ForeignKey("decks.id", ondelete="CASCADE"), primary_key=True),
    Column("word_id", BigInteger, ForeignKey("words.id", ondelete="CASCADE"), primary_key=True),
    Index("ix_deck_words_word_id", "word_id"),
)


class Deck(Base):
    __tablename__ = "decks"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    # 空 = 官方词表（所有人可读，只有管理员能改）
    owner_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    # private / public。官方词表不看这一列（owner_id IS NULL 即官方）
    visibility: Mapped[str] = mapped_column(
        String(16), nullable=False, server_default="private", default="private"
    )
    # 分享码：大写、只留字母数字（去掉用户输入的横线和空格），全局唯一。
    # 公开词表才有意义；改回私有时保留，但按码查不到。
    share_code: Mapped[str | None] = mapped_column(String(32), unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    owner: Mapped[User | None] = relationship(back_populates="decks", lazy="raise")
    words: Mapped[list["Word"]] = relationship(
        secondary=deck_words, back_populates="decks", lazy="raise"
    )


class Word(Base):
    """公共词库。普通用户没有写接口能碰到这张表。"""

    __tablename__ = "words"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    headword: Mapped[str] = mapped_column(String(200), nullable=False)
    phonetic: Mapped[str | None] = mapped_column(String(200))
    part_of_speech: Mapped[str | None] = mapped_column(String(60))
    meanings: Mapped[str | None] = mapped_column(Text)
    mnemonic: Mapped[str | None] = mapped_column(Text)
    roots: Mapped[str | None] = mapped_column(Text)
    derivations: Mapped[str | None] = mapped_column(Text)
    examples: Mapped[str | None] = mapped_column(Text)
    synonyms: Mapped[str | None] = mapped_column(Text)
    tags: Mapped[str | None] = mapped_column(Text)
    frequency: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    decks: Mapped[list[Deck]] = relationship(
        secondary=deck_words, back_populates="words", lazy="raise"
    )


# 词表名在同一范围内唯一：官方一张表一个名，每个人自己的表里也不能重名
Index(
    "ux_decks_official_name",
    Deck.name,
    unique=True,
    postgresql_where=Deck.owner_id.is_(None),
)
Index(
    "ux_decks_owner_name",
    Deck.owner_id,
    Deck.name,
    unique=True,
    postgresql_where=Deck.owner_id.isnot(None),
)

# 一个词只存一份：大小写不同视为同一个词（England / england）
Index("ux_words_headword_lower", func.lower(Word.headword), unique=True)
Index("ix_words_frequency", Word.frequency)


# ------------------------------------------------------------------ 私有词条


class UserWord(Base):
    """用户自己的词条。

    和公共词库 `words` 完全隔离：同一个拼写可以两边各有一份，互不影响；
    普通用户也永远改不到 `words`。挂到词表上走 `deck_user_words`。
    """

    __tablename__ = "user_words"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    headword: Mapped[str] = mapped_column(String(200), nullable=False)
    phonetic: Mapped[str | None] = mapped_column(String(200))
    part_of_speech: Mapped[str | None] = mapped_column(String(60))
    meanings: Mapped[str | None] = mapped_column(Text)
    mnemonic: Mapped[str | None] = mapped_column(Text)
    roots: Mapped[str | None] = mapped_column(Text)
    derivations: Mapped[str | None] = mapped_column(Text)
    examples: Mapped[str | None] = mapped_column(Text)
    synonyms: Mapped[str | None] = mapped_column(Text)
    tags: Mapped[str | None] = mapped_column(Text)
    frequency: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


# 同一个人的私有词库里，拼写不重复（和公共词库规则一致，忽略大小写）
Index("ux_user_words_user_headword", UserWord.user_id, func.lower(UserWord.headword), unique=True)


deck_user_words = Table(
    "deck_user_words",
    Base.metadata,
    Column(
        "deck_id",
        BigInteger,
        ForeignKey("decks.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    Column(
        "user_word_id",
        BigInteger,
        ForeignKey("user_words.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    Index("ix_deck_user_words_user_word_id", "user_word_id"),
)


# ------------------------------------------------------------------ 设备


class Device(Base):
    __tablename__ = "devices"

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    # 客户端自己生成的 uuid
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str | None] = mapped_column(String(120))
    platform: Mapped[str | None] = mapped_column(String(60))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


# ------------------------------------------------------------------ 学习进度


class WordState(Base):
    __tablename__ = "word_states"

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    headword_key: Mapped[str] = mapped_column(String(200), primary_key=True)
    headword: Mapped[str] = mapped_column(String(200), nullable=False)

    state: Mapped[str] = mapped_column(String(20), nullable=False, default="NEW")
    ease_factor: Mapped[float] = mapped_column(Float, nullable=False, default=2.5)
    interval_days: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    repetitions: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    learning_step: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    due_at_minutes: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    last_reviewed_at: Mapped[int | None] = mapped_column(BigInteger)
    lapses: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    # 客户端毫秒时间戳，LWW 的唯一依据
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    device_id: Mapped[str | None] = mapped_column(String(64))
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=REVISION_DEFAULT)

    __table_args__ = (
        Index("ix_word_states_revision", "revision"),
        Index("ix_word_states_user_due", "user_id", "due_at_minutes"),
    )


class ReviewLog(Base):
    """一次作答。只追加，不改不删，靠 `client_log_id` 幂等。"""

    __tablename__ = "review_logs"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    client_log_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), nullable=False)

    headword_key: Mapped[str] = mapped_column(String(200), nullable=False)
    headword: Mapped[str] = mapped_column(String(200), nullable=False)
    rating: Mapped[str] = mapped_column(String(16), nullable=False)
    reviewed_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    elapsed_ms: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    interval_days_after: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)

    device_id: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=REVISION_DEFAULT)

    __table_args__ = (
        # 同一个账号里日志 id 唯一即可；不同账号可以用同一个 id，互不影响
        Index("ux_review_logs_user_client", "user_id", "client_log_id", unique=True),
        Index("ix_review_logs_revision", "revision"),
        Index("ix_review_logs_user_headword", "user_id", "headword_key"),
        Index("ix_review_logs_reviewed_at", "reviewed_at"),
    )


# ------------------------------------------------------------------ 零碎状态


class UserTag(Base):
    __tablename__ = "user_tags"

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    name: Mapped[str] = mapped_column(String(80), primary_key=True)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    deleted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=REVISION_DEFAULT)

    __table_args__ = (Index("ix_user_tags_revision", "revision"),)


class WordUserTag(Base):
    __tablename__ = "word_user_tags"

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    headword_key: Mapped[str] = mapped_column(String(200), primary_key=True)
    tag_name: Mapped[str] = mapped_column(String(80), primary_key=True)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    deleted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=REVISION_DEFAULT)

    __table_args__ = (Index("ix_word_user_tags_revision", "revision"),)


class WordFlag(Base):
    __tablename__ = "word_flags"

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    headword_key: Mapped[str] = mapped_column(String(200), primary_key=True)
    priority: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    device_id: Mapped[str | None] = mapped_column(String(64))
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=REVISION_DEFAULT)

    __table_args__ = (Index("ix_word_flags_revision", "revision"),)


class Setting(Base):
    __tablename__ = "settings"

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    key: Mapped[str] = mapped_column(String(80), primary_key=True)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=REVISION_DEFAULT)

    __table_args__ = (Index("ix_settings_revision", "revision"),)
