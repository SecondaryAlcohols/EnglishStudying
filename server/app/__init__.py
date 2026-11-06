"""Windows 上 psycopg 的 async 实现只能用 SelectorEventLoop。

Python 3.8 起 Windows 默认是 ProactorEventLoop，psycopg 会直接抛
`Psycopg cannot use the 'ProactorEventLoop'`。在包导入时把策略切过来，
这样 uvicorn、alembic、CLI 三条入口都不用各写一遍。
"""

import asyncio
import sys

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
