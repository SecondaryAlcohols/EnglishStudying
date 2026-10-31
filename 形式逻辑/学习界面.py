"""学习界面的形式逻辑草图。

命名跟 Kotlin 侧对齐，两边可以逐行对照：
    ShowState.review_done_today  ↔ DailyProgress.reviewDoneToday
    ShowState.review_due_today   ↔ DailyProgress.todayDueReviewCount
    ShowState.started_today      ↔ DailyProgress.startedToday
    ShowState.mastered_today     ↔ DailyProgress.masteredToday
    ShowState.new_limit          ↔ SrsSettings.dailyNewLimit
    ShowState.elapsed_today      ↔ DailyProgress.elapsedTodayMs

约定：集合用复数（new_words / review_words），判断有无用 has_，读取用 get_，
当天计数器统一 _today 后缀，状态字段一律是名词短语、不带动词。
"""
import random
import time

RETRY_GAP = 10  # 答「模糊」后，隔多少个词再考一次

LEARNING_STEPS_MINUTES = [1, 10]  # 新词的学习步：两步都走完才毕业
GRADUATING_INTERVAL_DAYS = 1      # 毕业后的第一次天级间隔（天）
EXCLUDE_ANSWERS = ('too_easy', 'too_hard')  # 两种「移出学习」


class Database:
    new_words = [...]     # 没学过、还没进过队列的词
    review_words = [...]  # 学过、今天到期的词

    def get_new_words(self):
        return self.new_words.copy()

    def get_review_words(self):
        return self.review_words.copy()

    def has_new_words(self):
        pass

    def has_review_words(self):
        pass

    def record(self, *args):
        pass

    def update_word_state(self, word, answer):
        """按 [answer] 算排期并落库：写 review_states，同时追加一行 review_logs。

        日志行：word / answer / 作答时刻 / 作答耗时 / 这次算出来的间隔天数。
        """
        pass

    def log_answer(self, word, answer):
        """只追加一行 review_logs，review_states 原样。

        用在「排期已经定死、但用户确实又答了一次」的场合：间隔天数记当前的（锁定的）那个。
        """
        pass

    def exclude_word(self, word, answer):
        """移出学习：只把 state 改成 too_easy / too_hard，**不写日志**。

        移出不是一次作答，不该污染答题数；但它算复习过一遍（见「已复习」的统计口径）。
        """
        pass

    def is_review_stage(self, word):
        """这张卡现在是不是天级复习卡（REVIEW）—— 只有它才有「天级排期」可以定死。"""
        pass


class ShowState:
    review_done_today = ...  # 今日已复习数（出圈才算，见下面的复习循环）
    review_due_today = ...   # 今日到期的复习数（不是设置里的「每日复习上限」）

    started_today = ...      # 初学
    mastered_today = ...     # 掌握
    new_limit = ...          # 新词上限

    elapsed_today = ...      # 学习时间


@Database.record
def show(*args):
    Database.record(*args)
    return input("记得吗")


if __name__ == '__main__':
    db = Database()
    daily_target = ...  # 每天默认背的单词数

    today_new_words = random.sample(db.get_new_words(), daily_target)
    today_review_words = random.sample(db.get_review_words(), daily_target)
    db.record(today_new_words)
    db.record(time.time())
    ShowState.new_limit = daily_target  # 当天的新词上限，「太难」会从这里扣名额

    while db.has_new_words() or db.has_review_words():
        # 第一步 复习
        #
        # 复习卡反复考，直到「认识」才出圈。排期的规则只有三条：
        #   1. 第一次「模糊」把下次复习时间定死（按模糊算的天级间隔）；之后再点模糊不重算；
        #   2. 「认识」不改排期 —— 一次认识就出圈，复习不涉及学习步；
        #   3. 「忘记」等同于直接点忘记：排期重算、退回重学，进遗忘流程，不留在圈里。
        # 只有「模糊」会把词插回队列第 RETRY_GAP 个词之后接着考。
        #
        # 日志：每一次作答（模糊 / 认识 / 忘记）都落一行 review_logs —— 重考也是作答，
        # 答题数和用时才算得准；移出学习不写日志。只有「第一次模糊」和「忘记」会同时改
        # review_states，其余时候排期不动、只记日志。
        review_queue = list(today_review_words)
        locked_words = set()   # 排期已被第一次「模糊」定死的词
        relearning_words = []  # 忘记的词：一分钟后到期，自动回到队列重学
        excluded_words = []
        ShowState.review_done_today = 0
        ShowState.review_due_today = len(review_queue)

        while review_queue:
            word = review_queue.pop(0)
            answer = show(word, ...)

            if answer in EXCLUDE_ANSWERS:  # 太简单 / 太难：移出学习，出圈
                db.exclude_word(word, answer)  # 只改状态，不写日志
                excluded_words.append(word)
                locked_words.discard(word)
                ShowState.review_done_today += 1
                continue

            if answer == 'again':  # 忘记：等价于直接点忘记，交给遗忘流程
                db.update_word_state(word, answer)  # 状态 + 一行 AGAIN 日志
                locked_words.discard(word)
                relearning_words.append(word)
                continue

            if answer == 'hard':  # 模糊：第一次定死排期，之后只重考不改排期
                if word in locked_words:
                    db.log_answer(word, answer)  # 排期不动，但这次作答要留下
                else:
                    db.update_word_state(word, answer)  # 状态 + 一行 HARD 日志
                    # 只有本来就在 REVIEW 里的卡才谈得上「定死」—— 重学中的卡还在学习步里，
                    # 这一步没有天级排期，下次照样按步骤走
                    if db.is_review_stage(word):
                        locked_words.add(word)
                review_queue.insert(min(RETRY_GAP, len(review_queue)), word)
                continue

            # answer == 'good'：认识
            if word in locked_words:
                db.log_answer(word, answer)  # 圈里的认识：一行 GOOD 日志，排期不动
                locked_words.discard(word)
            else:
                db.update_word_state(word, answer)  # 没进过圈：状态 + 一行 GOOD 日志
            ShowState.review_done_today += 1  # 出圈才算复习完

        # 第二步 学习
        #
        # 新词按学习步走：1 分钟 → 10 分钟 → 毕业到天级间隔。学习步只决定两件事 ——
        # 今天还要不要再考它、毕业之后排到哪一天；重现是把卡放回队尾（不等真的到点，
        # 和现在的实现一致：到期时刻只是「今天还该不该考」的判据）。
        #
        # 三档对学习步的作用：
        #   认识 → 前进一步；走完最后一步就毕业（REVIEW，GRADUATING_INTERVAL_DAYS 天后）；
        #   模糊 → 原地踏步，用当前这一步的间隔再排一次，不前进也不毕业；
        #   忘记 → 退回第 0 步重来。
        # 太简单 / 太难 = 移出学习，不进学习步：
        #   太简单 → 初学 +1、掌握 +1（一眼就会，算学完）；
        #   太难   → 初学 / 掌握都不加，只把当天的新词上限扣掉一个
        #            （只影响显示和下一轮，已经发出去的这一队不会少词）。
        #
        # 注：真实的「初学」还包括「今天忘、今天又学回来」的旧词，那一摊在第一步；这里
        # 只写新词这部分。
        new_queue = list(today_new_words)
        learning_steps = {}     # word -> 已经走到第几步
        started_words = set()   # 今天开始学的新词
        mastered_words = set()  # 上面那些里今天学完的

        while new_queue:
            word = new_queue.pop(0)
            answer = show(word, ...)

            if answer in EXCLUDE_ANSWERS:
                db.exclude_word(word, answer)  # 只改状态，不写日志
                if answer == 'too_easy':
                    started_words.add(word)
                    mastered_words.add(word)
                else:
                    ShowState.new_limit -= 1
                continue

            if answer == 'good':
                learning_steps[word] = learning_steps.get(word, 0) + 1
                started_words.add(word)
            elif answer == 'again':
                learning_steps[word] = 0
            # 模糊：步数不动

            db.update_word_state(word, answer)  # 状态 + 一行日志

            if learning_steps.get(word, 0) >= len(LEARNING_STEPS_MINUTES):
                mastered_words.add(word)  # 最后一步走完：毕业，交给天级复习
                continue
            new_queue.append(word)        # 没毕业：放回队尾，今天还要再考

        ShowState.started_today = len(started_words)
        ShowState.mastered_today = len(mastered_words)
