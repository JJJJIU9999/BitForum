package com.bitforum.message;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「事务提交后再执行」的公共封装（M16 抽取）。
 *
 * <p><b>为什么需要它</b>：异步消息如果在事务内直接投递，消费者可能在事务提交前就查到旧状态。
 * M15 的知识库索引踩过这个坑：审核通过时文章在库里仍是 PENDING，消费者据此判定
 * 「不该在知识库中」而移除索引，事务提交后又不再触发索引 —— 造成永久漏索引。
 *
 * <p>M15 时这段逻辑内联在 {@code ArticleService}；M16 的评论发布也要发审核消息，
 * 于是抽成本组件统一使用，避免两处各写一份（写漏一处就是难查的时序 bug）。
 *
 * <p>当前无事务时直接执行，便于单元测试与消息本身不涉及事务的场景。
 */
@Component
public class AfterCommitExecutor {

    /**
     * 若当前处于事务中，则注册 afterCommit 回调；否则立即执行。
     *
     * <p>回调抛出的异常不会影响已提交的事务（事务此时已完成），
     * 因此调用方仍应自行捕获异常，避免消息发送失败被静默吞掉。
     */
    public void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
