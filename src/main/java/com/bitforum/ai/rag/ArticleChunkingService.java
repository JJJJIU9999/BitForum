package com.bitforum.ai.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 文章分块服务（M15）。
 *
 * <p>把文章正文切成适合向量化的片段。分块质量直接决定 RAG 召回效果：
 * 块太大，一段里混入多个主题，向量被"平均"得没有区分度；块太小，语义不完整。
 *
 * <p><b>分块规则</b>（参数见 application.yml 的 {@code bitforum.ai.rag.*}）：
 *
 * <ol>
 *   <li>先按空行切段落，超长段落再按中文标点切成句子；</li>
 *   <li>把句子贪心合并到接近 {@code chunk-size}（默认 350 字）为一块；
 *       选这个长度是因为嵌入模型 bge-base-zh-v1.5 的有效输入约 512 token（中文约 1 字 1 token），
 *       350 字留出余量，不会在模型侧被静默截断；</li>
 *   <li>相邻块之间带 {@code chunk-overlap}（默认 50 字）的尾部重叠，
 *       避免一个完整结论正好落在切分边界上而被切断；</li>
 *   <li>每块前置 {@code 《标题》} 作为上下文，使脱离全文的片段仍能自解释
 *       （检索命中单块时，模型看不到其它块）。</li>
 * </ol>
 */
@Service
public class ArticleChunkingService {

    /** 单块目标字符数（不含标题与重叠部分） */
    private final int targetChars;

    /** 相邻块之间的重叠字符数 */
    private final int overlapChars;

    public ArticleChunkingService(
            @Value("${bitforum.ai.rag.chunk-size:350}") int targetChars,
            @Value("${bitforum.ai.rag.chunk-overlap:50}") int overlapChars) {
        if (targetChars <= 0) {
            throw new IllegalArgumentException("分块长度必须为正数，实际：" + targetChars);
        }
        if (overlapChars < 0 || overlapChars >= targetChars) {
            throw new IllegalArgumentException(
                    "重叠长度必须大于等于 0 且小于分块长度，实际 overlap=" + overlapChars + ", size=" + targetChars);
        }
        this.targetChars = targetChars;
        this.overlapChars = overlapChars;
    }

    /** 一个分块：序号、最终内容（含标题与重叠）、字符数 */
    public record Chunk(int index, String content, int charCount) {
    }

    /**
     * 把一篇文章切成若干分块。
     *
     * @param title   文章标题，作为每块的上下文前缀
     * @param content 文章正文
     * @return 按顺序排列的分块；正文为空时返回空列表
     */
    public List<Chunk> chunk(String title, String content) {
        List<String> blocks = mergeUnits(splitUnits(normalize(content)));
        if (blocks.isEmpty()) {
            return List.of();
        }

        String safeTitle = title == null ? "" : title.trim();
        List<Chunk> chunks = new ArrayList<>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            String body = blocks.get(index);
            if (index > 0 && overlapChars > 0) {
                body = tail(blocks.get(index - 1), overlapChars) + body;
            }
            String withTitle = safeTitle.isEmpty() ? body : "《" + safeTitle + "》\n" + body;
            chunks.add(new Chunk(index, withTitle, withTitle.length()));
        }
        return chunks;
    }

    /** 统一换行符并压缩连续空行，避免把排版差异当成内容差异。 */
    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\r\n", "\n").replace("\r", "\n").replaceAll("\n{2,}", "\n");
    }

    /** 段落 → 合并单元：普通段落整体作为一个单元，超长段落先按句切开。 */
    private List<String> splitUnits(String text) {
        List<String> units = new ArrayList<>();
        for (String paragraph : text.split("\n+")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= targetChars) {
                units.add(trimmed);
            } else {
                units.addAll(splitLongParagraph(trimmed));
            }
        }
        return units;
    }

    /** 按中文/英文句末标点切分超长段落；没有标点的超长文本按固定长度硬切。 */
    private List<String> splitLongParagraph(String paragraph) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : paragraph.split("(?<=[。！？；!?;])")) {
            if (sentence.isBlank()) {
                continue;
            }
            if (sentence.length() > targetChars) {
                if (current.length() > 0) {
                    sentences.add(current.toString());
                    current.setLength(0);
                }
                for (int start = 0; start < sentence.length(); start += targetChars) {
                    sentences.add(sentence.substring(start, Math.min(start + targetChars, sentence.length())));
                }
                continue;
            }
            if (current.length() > 0 && current.length() + sentence.length() > targetChars) {
                sentences.add(current.toString());
                current.setLength(0);
            }
            current.append(sentence);
        }

        if (current.length() > 0) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    /** 把单元贪心合并成不超过目标长度的块。 */
    private List<String> mergeUnits(List<String> units) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String unit : units) {
            // 换行分隔符也要计入长度，否则块会超出目标长度一个字符
            int separatorLength = current.length() > 0 ? 1 : 0;
            if (current.length() > 0 && current.length() + separatorLength + unit.length() > targetChars) {
                blocks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }

        if (current.length() > 0) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private String tail(String text, int length) {
        return text.length() <= length ? text : text.substring(text.length() - length);
    }
}
