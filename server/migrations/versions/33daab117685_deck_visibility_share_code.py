"""词表可见性与分享码

给 decks 加两列：

* `visibility`：`private` / `public`。官方词表不看这一列（`owner_id IS NULL` 即官方，
  对所有人可见），所以这里不需要回填历史数据 —— 老词表默认 private，行为不变。
* `share_code`：分享码。大写、只留字母数字，全局唯一。老数据为 NULL（还没有码）；
  用户第一次把词表设为公开时服务端自动生成。

Revision ID: 33daab117685
Revises: d8e2a4f6b901
Create Date: 2026-11-06 14:50:40.793488

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '33daab117685'
down_revision: Union[str, Sequence[str], None] = 'd8e2a4f6b901'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column(
        'decks',
        sa.Column('visibility', sa.String(length=16), server_default='private', nullable=False),
    )
    op.add_column('decks', sa.Column('share_code', sa.String(length=32), nullable=True))
    # 唯一索引：两个词表不能共用一个分享码
    op.create_index(op.f('ix_decks_share_code'), 'decks', ['share_code'], unique=True)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_decks_share_code'), table_name='decks')
    op.drop_column('decks', 'share_code')
    op.drop_column('decks', 'visibility')
