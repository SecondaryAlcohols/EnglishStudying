"""账号、权限与按用户隔离的进度

- 新增 `users`（Argon2 密码哈希，角色 admin/user）。
- `decks` 加 `owner_id`：空 = 官方词表（所有人可读、仅管理员可写），非空 = 用户私有词表。
  原来 `name` 的全局唯一改成「官方范围内唯一」+「同一用户内唯一」两个部分唯一索引。
- 同步表全部加 `user_id` 并改复合主键，实现按账号隔离。

同步表在这版之前没有任何真实数据（只有联调时的测试行，已清空），所以这里直接
drop + recreate，比一列列 ALTER 更清楚，也不会留下孤立索引。

Revision ID: c7d1f0a2b3e4
Revises: bb975a190c64
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "c7d1f0a2b3e4"
down_revision: Union[str, Sequence[str], None] = "bb975a190c64"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

SYNC_TABLES = (
    "word_states",
    "review_logs",
    "user_tags",
    "word_user_tags",
    "word_flags",
    "settings",
    "devices",
)


def upgrade() -> None:
    # ---------------- 账号
    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), server_default=sa.text("uuidv7()"), nullable=False),
        sa.Column("username", sa.String(length=64), nullable=False),
        sa.Column("password_hash", sa.Text(), nullable=False),
        sa.Column("display_name", sa.String(length=120), nullable=True),
        sa.Column("role", sa.String(length=16), nullable=False),
        sa.Column("disabled", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("last_login_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("username"),
    )

    # ---------------- 词表归属
    op.add_column("decks", sa.Column("owner_id", sa.Uuid(), nullable=True))
    op.create_foreign_key(
        "decks_owner_id_fkey", "decks", "users", ["owner_id"], ["id"], ondelete="CASCADE"
    )
    op.create_index("ix_decks_owner_id", "decks", ["owner_id"], unique=False)

    # 原来 name 全局唯一 —— 多用户之后同一个名字可以被不同人各用一次
    op.drop_constraint("decks_name_key", "decks", type_="unique")
    op.create_index(
        "ux_decks_official_name",
        "decks",
        ["name"],
        unique=True,
        postgresql_where=sa.text("owner_id IS NULL"),
    )
    op.create_index(
        "ux_decks_owner_name",
        "decks",
        ["owner_id", "name"],
        unique=True,
        postgresql_where=sa.text("owner_id IS NOT NULL"),
    )

    # ---------------- 同步表重建（空表，直接换结构）
    for table in SYNC_TABLES:
        op.drop_table(table)

    op.create_table(
        "devices",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("name", sa.String(length=120), nullable=True),
        sa.Column("platform", sa.String(length=60), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "id"),
    )

    op.create_table(
        "word_states",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("headword", sa.String(length=200), nullable=False),
        sa.Column("state", sa.String(length=20), nullable=False),
        sa.Column("ease_factor", sa.Float(), nullable=False),
        sa.Column("interval_days", sa.Float(), nullable=False),
        sa.Column("repetitions", sa.Integer(), nullable=False),
        sa.Column("learning_step", sa.Integer(), nullable=False),
        sa.Column("due_at_minutes", sa.BigInteger(), nullable=False),
        sa.Column("last_reviewed_at", sa.BigInteger(), nullable=True),
        sa.Column("lapses", sa.Integer(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=True),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "headword_key"),
    )
    op.create_index("ix_word_states_revision", "word_states", ["revision"], unique=False)
    op.create_index("ix_word_states_user_due", "word_states", ["user_id", "due_at_minutes"], unique=False)

    op.create_table(
        "review_logs",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=False), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("client_log_id", sa.Uuid(), nullable=False),
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("headword", sa.String(length=200), nullable=False),
        sa.Column("rating", sa.String(length=16), nullable=False),
        sa.Column("reviewed_at", sa.BigInteger(), nullable=False),
        sa.Column("elapsed_ms", sa.BigInteger(), nullable=False),
        sa.Column("interval_days_after", sa.Float(), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ux_review_logs_user_client", "review_logs", ["user_id", "client_log_id"], unique=True)
    op.create_index("ix_review_logs_revision", "review_logs", ["revision"], unique=False)
    op.create_index("ix_review_logs_user_headword", "review_logs", ["user_id", "headword_key"], unique=False)
    op.create_index("ix_review_logs_reviewed_at", "review_logs", ["reviewed_at"], unique=False)

    op.create_table(
        "user_tags",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=80), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("deleted", sa.Boolean(), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "name"),
    )
    op.create_index("ix_user_tags_revision", "user_tags", ["revision"], unique=False)

    op.create_table(
        "word_user_tags",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("tag_name", sa.String(length=80), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("deleted", sa.Boolean(), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "headword_key", "tag_name"),
    )
    op.create_index("ix_word_user_tags_revision", "word_user_tags", ["revision"], unique=False)

    op.create_table(
        "word_flags",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("priority", sa.Boolean(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=True),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "headword_key"),
    )
    op.create_index("ix_word_flags_revision", "word_flags", ["revision"], unique=False)

    op.create_table(
        "settings",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("key", sa.String(length=80), nullable=False),
        sa.Column("value", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "key"),
    )
    op.create_index("ix_settings_revision", "settings", ["revision"], unique=False)


def downgrade() -> None:
    for table in SYNC_TABLES:
        op.drop_table(table)

    # 回到单用户版本的表结构
    op.create_table(
        "devices",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("name", sa.String(length=120), nullable=True),
        sa.Column("platform", sa.String(length=60), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "word_states",
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("headword", sa.String(length=200), nullable=False),
        sa.Column("state", sa.String(length=20), nullable=False),
        sa.Column("ease_factor", sa.Float(), nullable=False),
        sa.Column("interval_days", sa.Float(), nullable=False),
        sa.Column("repetitions", sa.Integer(), nullable=False),
        sa.Column("learning_step", sa.Integer(), nullable=False),
        sa.Column("due_at_minutes", sa.BigInteger(), nullable=False),
        sa.Column("last_reviewed_at", sa.BigInteger(), nullable=True),
        sa.Column("lapses", sa.Integer(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=True),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.PrimaryKeyConstraint("headword_key"),
    )
    op.create_index("ix_word_states_revision", "word_states", ["revision"], unique=False)
    op.create_index("ix_word_states_due", "word_states", ["due_at_minutes"], unique=False)
    op.create_table(
        "review_logs",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=False), nullable=False),
        sa.Column("client_log_id", sa.Uuid(), nullable=False),
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("headword", sa.String(length=200), nullable=False),
        sa.Column("rating", sa.String(length=16), nullable=False),
        sa.Column("reviewed_at", sa.BigInteger(), nullable=False),
        sa.Column("elapsed_ms", sa.BigInteger(), nullable=False),
        sa.Column("interval_days_after", sa.Float(), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("client_log_id"),
    )
    op.create_index("ix_review_logs_headword", "review_logs", ["headword_key"], unique=False)
    op.create_index("ix_review_logs_reviewed_at", "review_logs", ["reviewed_at"], unique=False)
    op.create_index("ix_review_logs_revision", "review_logs", ["revision"], unique=False)
    op.create_table(
        "user_tags",
        sa.Column("name", sa.String(length=80), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("deleted", sa.Boolean(), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.PrimaryKeyConstraint("name"),
    )
    op.create_index("ix_user_tags_revision", "user_tags", ["revision"], unique=False)
    op.create_table(
        "word_user_tags",
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("tag_name", sa.String(length=80), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("deleted", sa.Boolean(), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.PrimaryKeyConstraint("headword_key", "tag_name"),
    )
    op.create_index("ix_word_user_tags_revision", "word_user_tags", ["revision"], unique=False)
    op.create_table(
        "word_flags",
        sa.Column("headword_key", sa.String(length=200), nullable=False),
        sa.Column("priority", sa.Boolean(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=True),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.PrimaryKeyConstraint("headword_key"),
    )
    op.create_index("ix_word_flags_revision", "word_flags", ["revision"], unique=False)
    op.create_table(
        "settings",
        sa.Column("key", sa.String(length=80), nullable=False),
        sa.Column("value", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default=sa.text("nextval('sync_revision')"), nullable=False),
        sa.PrimaryKeyConstraint("key"),
    )
    op.create_index("ix_settings_revision", "settings", ["revision"], unique=False)

    op.drop_index("ux_decks_owner_name", table_name="decks")
    op.drop_index("ux_decks_official_name", table_name="decks")
    op.create_unique_constraint("decks_name_key", "decks", ["name"])
    op.drop_index("ix_decks_owner_id", table_name="decks")
    op.drop_constraint("decks_owner_id_fkey", "decks", type_="foreignkey")
    op.drop_column("decks", "owner_id")
    op.drop_table("users")
