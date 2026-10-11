package com.bitforum.ai.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运营洞察的异步执行器（M17）。
 *
 * <p><b>为什么用单线程池而不是消息队列</b>：MQ 适合"事件驱动、需要持久化与重试"的异步
 * （M15 的知识库索引就是这一类：文章发布 → 必须最终被索引）。
 * 而运营洞察是"管理员点一下、等几秒"的操作，用队列反而要额外定义消息体、消费者与幂等规则。
 * 更关键的是：{@code ai_insight_report} 里那条 PENDING 记录本身就是任务状态的持久化载体 ——
 * 服务重启后能看出"哪次生成没跑完"，不需要再来一套消息可靠性机制。
 *
 * <p><b>为什么是单线程</b>：洞察生成要调用大模型，成本与耗时都不低。
 * 单线程天然保证同一时刻只有一次生成在进行，避免管理员连点导致额度被并发烧掉。
 * 队列里堆积的任务会依次执行，先提交的先出结果。
 *
 * <p>线程设为 daemon：应用关闭时不阻塞退出；{@code destroyMethod} 用 shutdown
 * 语义（不再接新任务，但让已提交的任务跑完）。
 */
@Configuration
public class AiInsightConfig {

    @Bean(name = "insightExecutor", destroyMethod = "shutdown")
    public Executor insightExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ai-insight-generator");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }
}
