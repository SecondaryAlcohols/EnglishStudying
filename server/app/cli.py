"""命令行：迁移、导入词表、看列表、起服务。

用法（在 server/ 目录下）：

    uv run alembic upgrade head
    uv run python -m app.cli import-csv ../外部资源/考研5500/考研5500-L3.csv --use-deck-column
    uv run python -m app.cli list
    uv run python -m app.cli serve --port 8000
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from sqlalchemy import func, select

from .db import SessionLocal
from .importer import import_rows, parse_upload
from .models import Deck, User, Word, deck_words


def cmd_migrate(args: argparse.Namespace) -> int:
    from alembic import command
    from alembic.config import Config

    from .config import SERVER_DIR

    command.upgrade(Config(str(SERVER_DIR / "alembic.ini")), "head")
    print("数据库已迁移到最新版本")
    return 0


async def cmd_list(args: argparse.Namespace) -> int:
    async with SessionLocal() as session:
        rows = (await session.execute(
            select(Deck.id, Deck.name, func.count(deck_words.c.word_id))
            .outerjoin(deck_words, deck_words.c.deck_id == Deck.id)
            .group_by(Deck.id)
            .order_by(func.lower(Deck.name))
        )).all()
        if not rows:
            print("还没有词表")
            return 0
        width = max(len(name) for _, name, _ in rows)
        for deck_id, name, count in rows:
            print(f"{deck_id:>4}  {name:<{width}}  {count:>6} 词")
        total = await session.scalar(select(func.count()).select_from(Word)) or 0
        print(f"\n词条池共 {total} 个词（同一个词可属于多张词表，只存一份）")
    return 0


async def _deck_for(session, name: str, description: str | None) -> Deck:
    deck = await session.scalar(select(Deck).where(Deck.name == name))
    if deck is None:
        deck = Deck(name=name, description=description)
        session.add(deck)
        await session.flush()
        print(f"  + 新建词表「{name}」")
    return deck


async def cmd_import_csv(args: argparse.Namespace) -> int:
    async with SessionLocal() as session:
        for raw_path in args.files:
            path = Path(raw_path)
            if not path.exists():
                print(f"找不到文件：{path}", file=sys.stderr)
                return 1
            rows = parse_upload(path.name, path.read_bytes())
            if not rows:
                print(f"{path.name}：没有解析出任何行", file=sys.stderr)
                continue

            if args.use_deck_column:
                grouped: dict[str, list] = {}
                for row in rows:
                    grouped.setdefault(row.values.get("deck") or args.deck or path.stem, []).append(row)
            else:
                grouped = {args.deck or path.stem: rows}

            print(f"{path.name}：{len(rows)} 行")
            for deck_name, deck_rows in grouped.items():
                deck = await _deck_for(session, deck_name, args.description)
                stats = await import_rows(session, deck, deck_rows, replace_links=args.replace)
                await session.commit()
                print(
                    f"  → 「{deck.name}」新建 {stats.created} / 更新 {stats.updated} / "
                    f"未变 {stats.unchanged} / 新挂载 {stats.linked}"
                    + (f" / 重复 {len(stats.duplicate_lines)}" if stats.duplicate_lines else "")
                )
                for issue in stats.issues[:5]:
                    print(f"    ! {issue}")
    return 0


async def cmd_delete(args: argparse.Namespace) -> int:
    async with SessionLocal() as session:
        deck = await session.scalar(select(Deck).where(Deck.name == args.name))
        if deck is None:
            print(f"没有叫「{args.name}」的词表", file=sys.stderr)
            return 1
        await session.delete(deck)
        await session.commit()
        print(f"已删除词表「{args.name}」")
    return 0


async def cmd_serve(args: argparse.Namespace) -> int:
    import uvicorn

    from .config import get_settings

    settings = get_settings()
    host = args.host or settings.host
    port = args.port or settings.port
    print(f"服务地址：http://{host}:{port}  （手机用电脑的局域网 IP 访问）")
    await uvicorn.Server(uvicorn.Config("app.main:app", host=host, port=port)).serve()
    return 0


async def cmd_create_user(args: argparse.Namespace) -> int:
    from .security import hash_password

    username = args.username.strip().lower()
    async with SessionLocal() as session:
        taken = await session.scalar(select(User.id).where(User.username == username))
        if taken is not None:
            print(f"用户名「{username}」已存在", file=sys.stderr)
            return 1
        existing = await session.scalar(select(func.count()).select_from(User)) or 0
        role = "admin" if (args.admin or existing == 0) else "user"
        user = User(
            username=username,
            password_hash=hash_password(args.password),
            display_name=args.display_name,
            role=role,
        )
        session.add(user)
        await session.commit()
        print(f"已创建用户「{username}」，角色 {role}")
    return 0


async def cmd_users(args: argparse.Namespace) -> int:
    async with SessionLocal() as session:
        users = list(await session.scalars(select(User).order_by(User.created_at)))
        if not users:
            print("还没有任何账号（注册页的第一个用户会成为管理员）")
            return 0
        for user in users:
            print(f"{user.username:<20} {user.role:<6} {user.created_at:%Y-%m-%d %H:%M}  {user.display_name or ''}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m app.cli", description="词表服务管理工具")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("migrate", help="把数据库迁移到最新版本（alembic upgrade head）")
    p.set_defaults(func=cmd_migrate, sync=True)

    p = sub.add_parser("list", help="列出词表")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("import-csv", help="导入 CSV / JSON")
    p.add_argument("files", nargs="+", help="一个或多个文件")
    p.add_argument("--deck", help="词表名；不填则用文件名（或文件里的 deck 列）")
    p.add_argument("--description", help="词表描述")
    p.add_argument("--use-deck-column", action="store_true", help="按文件里的 deck 列拆分词表")
    p.add_argument("--replace", action="store_true", help="先清空该词表已有的挂载再导入")
    p.set_defaults(func=cmd_import_csv)

    p = sub.add_parser("create-user", help="创建账号（库里没有用户时自动给 admin）")
    p.add_argument("username")
    p.add_argument("password")
    p.add_argument("--admin", action="store_true", help="强制给管理员角色")
    p.add_argument("--display-name", dest="display_name")
    p.set_defaults(func=cmd_create_user)

    p = sub.add_parser("users", help="列出账号")
    p.set_defaults(func=cmd_users)

    p = sub.add_parser("delete", help="按名字删除词表")
    p.add_argument("name")
    p.set_defaults(func=cmd_delete)

    p = sub.add_parser("serve", help="启动 HTTP 服务")
    p.add_argument("--host")
    p.add_argument("--port", type=int)
    p.set_defaults(func=cmd_serve)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if getattr(args, "sync", False):
        return args.func(args)
    return asyncio.run(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
