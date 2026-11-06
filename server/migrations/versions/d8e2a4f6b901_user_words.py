"""私有词条：user_words + deck_user_words

公共词库 `words` 继续由管理员维护、普通用户只读；用户想加自己的词，写这张新表。
词表挂载因此有两张关系表：`deck_words`（公共词）和 `deck_user_words`（私有词）。

Revision ID: d8e2a4f6b901
Revises: c7d1f0a2b3e4
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "d8e2a4f6b901"
down_revision: Union[str, Sequence[str], None] = "c7d1f0a2b3e4"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "user_words",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=False), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("headword", sa.String(length=200), nullable=False),
        sa.Column("phonetic", sa.String(length=200), nullable=True),
        sa.Column("part_of_speech", sa.String(length=60), nullable=True),
        sa.Column("meanings", sa.Text(), nullable=True),
        sa.Column("mnemonic", sa.Text(), nullable=True),
        sa.Column("roots", sa.Text(), nullable=True),
        sa.Column("derivations", sa.Text(), nullable=True),
        sa.Column("examples", sa.Text(), nullable=True),
        sa.Column("synonyms", sa.Text(), nullable=True),
        sa.Column("tags", sa.Text(), nullable=True),
        sa.Column("frequency", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    # 同一个人的私有库里拼写不重复（忽略大小写）
    op.create_index(
        "ux_user_words_user_headword",
        "user_words",
        ["user_id", sa.text("lower(headword)")],
        unique=True,
    )

    op.create_table(
        "deck_user_words",
        sa.Column("deck_id", sa.BigInteger(), nullable=False),
        sa.Column("user_word_id", sa.BigInteger(), nullable=False),
        sa.ForeignKeyConstraint(["deck_id"], ["decks.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_word_id"], ["user_words.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("deck_id", "user_word_id"),
    )
    op.create_index(
        "ix_deck_user_words_user_word_id", "deck_user_words", ["user_word_id"], unique=False
    )


def downgrade() -> None:
    op.drop_index("ix_deck_user_words_user_word_id", table_name="deck_user_words")
    op.drop_table("deck_user_words")
    op.drop_index("ux_user_words_user_headword", table_name="user_words")
    op.drop_table("user_words")
