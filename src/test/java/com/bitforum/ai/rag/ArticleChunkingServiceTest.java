package com.bitforum.ai.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 文章分块服务单元测试（M15）。
 *
 * <p>分块是纯字符串逻辑，不需要 Spring 上下文，因此直接 new 出来测，跑得快也容易定位问题。
 * 默认参数与生产一致：350 字一块、相邻重叠 50 字。
 */
class ArticleChunkingServiceTest {

    private static final int CHUNK_SIZE = 350;
    private static final int OVERLAP = 50;

    private final ArticleChunkingService service = new ArticleChunkingService(CHUNK_SIZE, OVERLAP);

    @Test
    void shortArticleShouldProduceSingleChunkWithTitlePrefix() {
        List<ArticleChunkingService.Chunk> chunks = service.chunk("如何发帖", "登录后点击写文章按钮即可发布。");

        assertEquals(1, chunks.size(), "短文章应只切出一块");
        assertEquals(0, chunks.get(0).index());
        assertTrue(chunks.get(0).content().startsWith("《如何发帖》"), "每块应带标题作为上下文");
        assertTrue(chunks.get(0).content().contains("登录后点击写文章按钮即可发布"));
        assertEquals(chunks.get(0).content().length(), chunks.get(0).charCount());
    }

    @Test
    void longArticleShouldSplitIntoMultipleBoundedChunks() {
        String paragraph = "这是一个用于验证分块逻辑的段落，它需要足够长才能触发切分行为。";
        String content = String.join("\n\n", java.util.Collections.nCopies(30, paragraph));

        List<ArticleChunkingService.Chunk> chunks = service.chunk("长文测试", content);

        assertTrue(chunks.size() > 1, "超过单块长度的文章应被切成多块，实际块数：" + chunks.size());
        for (ArticleChunkingService.Chunk chunk : chunks) {
            // 上限 = 标题前缀 + 目标长度 + 重叠长度
            int upperBound = CHUNK_SIZE + OVERLAP + "《长文测试》\n".length();
            assertTrue(
                    chunk.charCount() <= upperBound,
                    "单块长度不应超过上限 " + upperBound + "，实际：" + chunk.charCount());
        }
        for (int index = 0; index < chunks.size(); index++) {
            assertEquals(index, chunks.get(index).index(), "分块序号应连续且从 0 开始");
        }
    }

    @Test
    void adjacentChunksShouldShareOverlap() {
        String paragraph = "重叠校验段落，用于确认相邻分块之间存在共享内容以避免语义被切断。";
        String content = String.join("\n", java.util.Collections.nCopies(30, paragraph));

        List<ArticleChunkingService.Chunk> chunks = service.chunk("重叠测试", content);
        assertTrue(chunks.size() > 1, "需要至少两块才能校验重叠");

        String previous = chunks.get(0).content();
        String current = chunks.get(1).content();
        String expectedOverlap = previous.substring(previous.length() - OVERLAP);

        assertTrue(
                current.contains(expectedOverlap),
                "第二块开头应包含上一块结尾的 " + OVERLAP + " 字重叠内容");
    }

    @Test
    void veryLongParagraphWithoutPunctuationShouldBeHardSplit() {
        String content = "无标点".repeat(400);

        List<ArticleChunkingService.Chunk> chunks = service.chunk("无标点测试", content);

        assertTrue(chunks.size() > 1, "无标点超长文本也必须被切开，否则会超出模型输入上限");
        for (ArticleChunkingService.Chunk chunk : chunks) {
            int upperBound = CHUNK_SIZE + OVERLAP + "《无标点测试》\n".length();
            assertTrue(chunk.charCount() <= upperBound, "硬切后的块同样不能超过上限，实际：" + chunk.charCount());
        }
    }

    @Test
    void blankContentShouldProduceNoChunk() {
        assertTrue(service.chunk("空文章", "   \n\n  ").isEmpty(), "正文为空时不应产出分块");
        assertTrue(service.chunk("空文章", null).isEmpty(), "正文为 null 时不应产出分块");
        assertTrue(service.chunk(null, "有内容但没有标题").size() == 1, "缺少标题时仍应正常分块");
    }

    @Test
    void invalidParametersShouldFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new ArticleChunkingService(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArticleChunkingService(100, 100));
        assertThrows(IllegalArgumentException.class, () -> new ArticleChunkingService(100, -1));
    }
}
