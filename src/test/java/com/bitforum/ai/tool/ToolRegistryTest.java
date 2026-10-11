package com.bitforum.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import com.bitforum.ai.agent.AgentType;

/**
 * 工具注册表单元测试（M14；M1 增加只读白名单约束）。
 *
 * 重点验证最小权限约束：问答助手可以拿到工具，而审核/运营/推荐 Agent
 * 当前不能拿到任何工具。这条约束在 M16 接入审核 Agent 时尤其关键 ——
 * 审核 Agent 绝不应该拥有"发文章""点赞""关注"这类写能力。
 *
 * M1 起还要验证：**默认只把只读工具交给模型**，写工具即使仍在工具类里也必须被过滤掉。
 */
class ToolRegistryTest {

    private final ArticleTools articleTools = new ArticleTools(null, null, null, null);
    private final UserInteractionTools interactionTools =
            new UserInteractionTools(null, null, null, null);

    @Test
    void qaAgentShouldReceiveQueryAndInteractionTools() {
        Object[] tools = ToolRegistry.toolsFor(AgentType.QA, articleTools, interactionTools);

        assertEquals(2, tools.length, "问答助手应拿到文章工具与互动工具两组");
        assertEquals(articleTools, tools[0]);
        assertEquals(interactionTools, tools[1]);
    }

    @Test
    void nonQaAgentsShouldNotReceiveAnyToolYet() {
        for (AgentType type : new AgentType[]{AgentType.MODERATION, AgentType.ANALYST, AgentType.RECOMMEND}) {
            Object[] tools = ToolRegistry.toolsFor(type, articleTools, interactionTools);
            assertEquals(0, tools.length,
                    type + " 当前不应装配任何工具（M16/M17 接入时需显式设计其最小权限工具集）");
        }
    }

    @Test
    void nullTypeShouldReturnEmptyInsteadOfFailing() {
        assertEquals(0, ToolRegistry.toolsFor(null, articleTools, interactionTools).length,
                "类型为空时应安全返回空数组，而不是抛异常打断对话");
    }

    @Test
    void toolRegistryConstantsShouldBeStable() {
        // 工具名会出现在模型看到的 schema 中，改动会影响提示词与历史会话的可复现性
        assertNotNull(AgentType.QA.name());
        assertTrue(AgentType.QA.getDisplayName().contains("问答"));
    }

    /**
     * M1 核心断言：只读过滤后，模型可见工具恰好是白名单，且一个写工具都不剩。
     */
    @Test
    void onlyReadOnlyShouldDropEveryWriteTool() {
        ToolCallback[] all = ToolCallbacks.from(
                ToolRegistry.toolsFor(AgentType.QA, articleTools, interactionTools));
        Set<String> allNames = names(all);
        assertTrue(allNames.containsAll(ToolRegistry.WRITE_TOOL_NAMES),
                "写工具方法名清单必须与实际工具方法一致，实际工具：" + allNames);

        Set<String> readOnlyNames = names(ToolRegistry.onlyReadOnly(all));

        assertEquals(ToolRegistry.QA_READ_ONLY_TOOL_NAMES, readOnlyNames,
                "过滤后应恰好剩下只读白名单");
        assertTrue(Collections.disjoint(readOnlyNames, ToolRegistry.WRITE_TOOL_NAMES),
                "写工具不得出现在模型可见列表里");
    }

    /**
     * 防回归：读 + 写两份清单必须完整覆盖工具类上的全部 {@code @Tool} 方法。
     *
     * <p>将来给工具类新增方法时，如果忘了把它归类，这条断言会失败 —— 提醒作者显式决定
     * 它是只读还是写操作，而不是让新方法默认溜进模型可见列表。
     */
    @Test
    void everyToolMethodShouldBeClassifiedAsReadOnlyOrWrite() {
        Set<String> allNames = names(ToolCallbacks.from(
                ToolRegistry.toolsFor(AgentType.QA, articleTools, interactionTools)));

        Set<String> classified = new java.util.HashSet<>(ToolRegistry.QA_READ_ONLY_TOOL_NAMES);
        classified.addAll(ToolRegistry.WRITE_TOOL_NAMES);

        assertEquals(allNames, classified,
                "新增工具方法必须显式登记为只读或写操作，否则默认不会暴露给模型");
    }

    @Test
    void onlyReadOnlyShouldHandleNullAndEmptySafely() {
        assertEquals(0, ToolRegistry.onlyReadOnly(null).length);
        assertEquals(0, ToolRegistry.onlyReadOnly(new ToolCallback[0]).length);
    }

    private Set<String> names(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
